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
        lastLocalPath = null
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
            // 2026-10-22 : un .srt en ligne (virgules dans les heures) est aussi accepté.
            cues = withContext(Dispatchers.Default) {
                parseVtt((ConversionAss.versVtt(vtt) ?: vtt).lines().joinToString("\n") { l -> if (l.contains("-->")) l.replace(',', '.') else l })
            }
            Log.d(TAG, "Parsed ${cues.size} cues from $vttUrl")
            startTicking()
        }
    }

    /**
     * 2026-10-16 (user : « quand on télécharge le sous-titre, le serveur devient rouge ») :
     * un sous-titre téléchargé (OpenSubtitles/SubDL, fichier .srt/.vtt local) était appliqué
     * en RE-PRÉPARANT ExoPlayer (setMediaItem) → nouvelle requête du manifeste → 403 sur les
     * serveurs à URL signée/usage unique (megaplay & co) → serveur marqué en échec. On passe
     * désormais par cet overlay, 100 % isolé du lecteur : la vidéo ne bouge pas.
     * Lit le fichier (UTF-8, repli latin-1 si illisible), accepte SRT (virgules dans les
     * timestamps) et VTT.
     */
    fun startLocal(path: String) {
        stop()
        lastVttUrl = null
        lastLocalPath = path
        globalInstance = this
        ensureAttached()
        applyPrefs()
        subtitleView.setCues(emptyList())
        fetchJob = scope.launch {
            val texte = try {
                withContext(Dispatchers.IO) {
                    val f = java.io.File(path.removePrefix("file://").let { java.net.URLDecoder.decode(it, "UTF-8") })
                    val bytes = f.readBytes()
                    val utf8 = String(bytes, Charsets.UTF_8)
                    if (utf8.count { it == '�' } > 3) String(bytes, Charsets.ISO_8859_1) else utf8
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Local subtitle read error: ${t.message}")
                null
            } ?: return@launch
            // SRT → VTT : « 00:01:02,345 --> 00:01:04,000 » devient « 00:01:02.345 --> 00:01:04.000 ».
            //   2026-10-22 : un .ass/.ssa est d'abord converti (cf. assVersVtt).
            // 2026-10-22 (mesuré sur la télé : « Slow dispatch 3,7 à 4,4 s » sur le fil de l'écran au
            //   chargement d'un sous-titre) — conversion et lecture des répliques hors du fil de l'écran.
            cues = withContext(Dispatchers.Default) {
                parseVtt((ConversionAss.versVtt(texte) ?: texte).lines().joinToString("\n") { l ->
                    if (l.contains("-->")) l.replace(',', '.') else l
                })
            }
            Log.d(TAG, "Parsed ${cues.size} cues from local $path")
            startTicking()
            // 2026-10-22 : sous-titre ajouté (téléchargé…) sans sous-titre serveur pour le recaler
            //   → calage automatique à l'écoute des voix (s'arrête une fois calé).
            try {
                val refs = withContext(Dispatchers.IO) { RecalageAuto.references(SyncSousTitres.videoCourante, sauf = path) }
                if (refs.isEmpty()) {
                    EcouteVoix.lancer(context, player, repliques(), path) { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) { Log.w(TAG, "écoute des voix : ${t.message}") }
        }
    }
    private var lastLocalPath: String? = null

    /** Heures des répliques chargées (recalage automatique). */
    fun repliques(): List<RecalageAuto.Replique> = cues.map { RecalageAuto.Replique(it.startMs, it.endMs) }

    fun stop() {
        EcouteVoix.arreter()
        tickJob?.cancel(); tickJob = null
        fetchJob?.cancel(); fetchJob = null
        cues = emptyList()
        try { subtitleView.setCues(emptyList()) } catch (_: Throwable) {}
    }

    /** Fichier / adresse du sous-titre affiché (recalage automatique), null si arrêté. */
    fun sourceCourante(): String? = if (isRunning()) (lastLocalPath ?: lastVttUrl) else null

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
        lastLocalPath?.let { startLocal(it); return }
        val url = lastVttUrl ?: return
        start(url, lastReferer)
    }

    /** Réapplique les prefs subtitle (taille, couleur/style, marge). Appeler
     *  si les prefs changent en cours de lecture. */
    /** 2026-10-22 : hauteur ajoutée sous les sous-titres pendant que la barre de contrôle est affichée. */
    @Volatile private var leveePx = 0

    /** 2026-10-22 — remonte les sous-titres au-dessus de la barre de contrôle (0 = en bas). */
    fun lever(px: Int) {
        if (leveePx == px) return
        leveePx = px
        applyPrefs()
    }

    fun applyPrefs() {
        try {
            subtitleView.setFractionalTextSize(
                SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize,
            )
            subtitleView.setStyle(UserPreferences.captionStyle)
            (subtitleView.layoutParams as? FrameLayout.LayoutParams)?.apply {
                bottomMargin = UserPreferences.captionMargin
                    .let { dp -> (context.resources.displayMetrics.density * dp).toInt() } + leveePx
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
                // 2026-10-22 : décalage / dérive réglés dans « Synchroniser les sous-titres ».
                val pos = SyncSousTitres.tempsSousTitre(try { player.currentPosition } catch (_: Throwable) { 0L })
                val active = if (SyncSousTitres.enAttenteCalage) emptyList()
                    else cues.filter { it.startMs <= pos && pos <= it.endMs }
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
        // 2026-10-22 (user : « la marge des sous-titres est à 0 mais sur la télé ils ne sont pas tout à
        //   fait en bas ») — SubtitleView garde par défaut 8 % de la hauteur sous le texte, EN PLUS de la
        //   marge réglée. On le ramène à 0 : la marge choisie est désormais la seule distance au bas.
        setBottomPaddingFraction(0f)
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
