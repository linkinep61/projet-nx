package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import com.streamflixreborn.streamflix.extractors.Extractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 2026-06-09 — Overlay sous-titres COMPLÈTEMENT ISOLÉ d'ExoPlayer/Media3.
 *
 *  Pourquoi : Media3 1.8 TextRenderer crashe sur assertLegacyDecodingEnabledIfRequired
 *  dès qu'on ajoute des subs externes via MergingMediaSource. Au lieu de
 *  triturer la pipeline Media3, on fait le rendu nous-mêmes :
 *   1. Télécharge le VTT en background
 *   2. Parse les cues (start, end, texte)
 *   3. Loop 200ms qui lit player.currentPosition et update une SubtitleView
 *      ajoutée sur le PlayerView parent
 *
 *  2026-06-09 v2 (user "tu peux pas le régler pour qu'il se connecte au
 *  nôtre qu'on puisse faire des réglages dessus") : utilise androidx.media3.ui.
 *  SubtitleView (= la même que ExoPlayer) + applique les UserPreferences
 *  captionTextSize / captionStyle / captionMargin → les réglages standard
 *  (taille, couleur, fond, marge) déjà présents dans Settings s'appliquent
 *  AUTOMATIQUEMENT à nos subs externes.
 *
 *  Si le VTT échoue → SubtitleView reste vide, vidéo continue normalement.
 *  Aucun risque pour la vidéo principale.
 */
class ExternalSubtitleOverlay(
    private val context: Context,
    private val playerViewParent: ViewGroup,
    private val player: Player,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickJob: Job? = null
    private var fetchJob: Job? = null
    private var cues: List<TimedCue> = emptyList()
    private val subtitleView: SubtitleView by lazy { createSubtitleView() }
    private var lastVttUrl: String? = null
    private var lastReferer: String = "https://vidzy.live/"

    data class TimedCue(val startMs: Long, val endMs: Long, val text: CharSequence)

    fun start(vttUrl: String, referer: String = "https://vidzy.live/") {
        stop()
        lastVttUrl = vttUrl
        lastReferer = referer
        globalInstance = this
        ensureAttached()
        applyPrefs()
        subtitleView.setCues(emptyList())
        fetchJob = scope.launch {
            val vtt = try {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url(vttUrl)
                        .header("Referer", referer)
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    Extractor.sharedClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "VTT fetch failed: HTTP ${resp.code} for $vttUrl")
                            null
                        } else resp.body?.string()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "VTT fetch error: ${t.message}")
                null
            } ?: return@launch
            cues = parseVtt(vtt)
            Log.d(TAG, "Parsed ${cues.size} cues from $vttUrl")
            startTicking()
        }
    }

    fun stop() {
        tickJob?.cancel(); tickJob = null
        fetchJob?.cancel(); fetchJob = null
        cues = emptyList()
        try { subtitleView.setCues(emptyList()) } catch (_: Throwable) {}
    }

    /** True si l'overlay est en cours d'affichage des cues. */
    fun isRunning(): Boolean = tickJob?.isActive == true

    fun release() {
        stop()
        if (globalInstance === this) globalInstance = null
        lastVttUrl = null
        try { playerViewParent.removeView(subtitleView) } catch (_: Throwable) {}
    }

    /** Re-démarre l'overlay avec la dernière URL connue (= utilisé par
     *  startGlobalIfPossible quand l'user re-sélectionne les subs externes). */
    fun restartLast() {
        val url = lastVttUrl ?: return
        start(url, lastReferer)
    }

    /** Réapplique les prefs subtitle (taille, couleur/style, marge). Appeler
     *  si les prefs changent en cours de lecture. */
    fun applyPrefs() {
        try {
            subtitleView.setFractionalTextSize(
                SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize,
            )
            subtitleView.setStyle(UserPreferences.captionStyle)
            (subtitleView.layoutParams as? FrameLayout.LayoutParams)?.apply {
                bottomMargin = UserPreferences.captionMargin
                    .let { dp -> (context.resources.displayMetrics.density * dp).toInt() }
            }?.let { subtitleView.layoutParams = it }
        } catch (_: Throwable) {}
    }

    private fun startTicking() {
        tickJob = scope.launch {
            var tickCount = 0
            while (isActive) {
                // 2026-06-09 (user "les réglages du texte etc c'est pas
                //   vraiment connecté") : ré-applique les prefs subtitle
                //   toutes les 2s, comme ça les changements de taille/
                //   couleur/marge dans le menu Style s'appliquent live.
                tickCount++
                if (tickCount % 10 == 1) applyPrefs()
                val pos = try { player.currentPosition } catch (_: Throwable) { 0L }
                val active = cues.filter { it.startMs <= pos && pos <= it.endMs }
                // 2026-06-09 (user "parfois plusieurs écritures s'empilent") :
                //   plusieurs cues simultanées sont COMBINÉES en un seul bloc
                //   avec retours ligne, au lieu d'envoyer plusieurs Cue à
                //   SubtitleView qui les superposait à la même position.
                val mediaCues = if (active.isEmpty()) emptyList()
                else {
                    val combined = android.text.SpannableStringBuilder()
                    active.forEachIndexed { idx, cue ->
                        if (idx > 0) combined.append("\n")
                        combined.append(cue.text)
                    }
                    listOf(Cue.Builder().setText(combined).build())
                }
                try { subtitleView.setCues(mediaCues) } catch (_: Throwable) {}
                delay(200)
            }
        }
    }

    private fun ensureAttached() {
        if (subtitleView.parent == null) {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            try { playerViewParent.addView(subtitleView, lp) } catch (_: Throwable) {}
        }
    }

    private fun createSubtitleView(): SubtitleView = SubtitleView(context).apply {
        // L'utilisateur règle taille / couleur / marge via Paramètres
        //   → Sous-titres. Ces valeurs sont appliquées via applyPrefs() au
        //   start (ne pas reproduire ici).
    }

    /** Parser WEBVTT minimal — gère `HH:MM:SS.mmm` et `MM:SS.mmm`, blocs séparés
     *  par lignes vides. Garde les balises HTML simples (`<b>`, `<i>`...) en
     *  les convertissant en spans Android. */
    private fun parseVtt(content: String): List<TimedCue> {
        val result = mutableListOf<TimedCue>()
        val lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val (startStr, endStr) = line.split("-->").let {
                    it[0].trim() to it[1].trim().split(" ").first()
                }
                val start = parseTs(startStr)
                val end = parseTs(endStr)
                if (start >= 0 && end >= start) {
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && lines[i].trim().isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("<br/>")
                        sb.append(lines[i])
                        i++
                    }
                    val htmlText = sb.toString().trim()
                    if (htmlText.isNotEmpty()) {
                        val spanned: CharSequence = try {
                            Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY)
                        } catch (_: Throwable) {
                            htmlText.replace(Regex("<[^>]+>"), "")
                        }
                        result.add(TimedCue(start, end, spanned))
                    }
                }
            }
            i++
        }
        return result
    }

    private fun parseTs(s: String): Long {
        return try {
            val parts = s.split(":")
            var h = 0; var m = 0; var rest = ""
            when (parts.size) {
                3 -> { h = parts[0].toInt(); m = parts[1].toInt(); rest = parts[2] }
                2 -> { m = parts[0].toInt(); rest = parts[1] }
                else -> return -1L
            }
            val (sec, ms) = rest.split(".", limit = 2).let {
                it[0].toInt() to (it.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toInt() ?: 0)
            }
            (h * 3600 + m * 60 + sec) * 1000L + ms
        } catch (_: Throwable) { -1L }
    }

    companion object {
        private const val TAG = "ExternalSubOverlay"

        /** Instance globale active — utilisée par PlayerSettingsView pour
         *  permettre à l'item "Désactivé" du picker subs de stopper notre
         *  overlay sans dépendance directe sur PlayerTvFragment. */
        @Volatile @JvmStatic
        var globalInstance: ExternalSubtitleOverlay? = null

        @JvmStatic fun stopGlobal() {
            try { globalInstance?.stop() } catch (_: Throwable) {}
        }

        @JvmStatic fun startGlobalIfPossible() {
            try { globalInstance?.restartLast() } catch (_: Throwable) {}
        }
    }
}
