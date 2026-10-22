package com.streamflixreborn.streamflix.utils

import android.util.Log

/** Conversion des sous-titres ASS/SSA (animes) en WebVTT — partagée par le calque et la traduction. */
object ConversionAss {

    /**
     * 2026-10-22 (user, AnimeSama : « les sous-titres dedans ne fonctionnent pas ») — le
     * sous-titre téléchargé était un .ass (Advanced SubStation, format des animes, ici
     * Crunchyroll) : « Parsed 0 cues », rien ne s'affichait. Converti ici en VTT :
     *  - lignes « Dialogue: » de la section [Events], champs lus d'après la ligne « Format: » ;
     *  - heures « 0:01:02.34 » (centièmes) → « 00:01:02.340 » ;
     *  - balises de style {…} retirées, \N / \n → retour à la ligne, \h → espace.
     * Rend null si ce n'est pas un fichier ASS/SSA (SRT et VTT passent tels quels).
     */
    fun versVtt(texte: String): String? {
        if (!texte.contains("[Events]", ignoreCase = true) || !texte.contains("Dialogue:")) return null
        var champs = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val lignes = mutableListOf<Triple<Long, Long, String>>()
        fun heure(h: String): Long = runCatching {
            val p = h.trim().split(':')
            val sec = p[2].split('.')
            val cs = sec.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toInt() ?: 0
            (p[0].toInt() * 3600L + p[1].toInt() * 60L + sec[0].toInt()) * 1000L + cs * 10L
        }.getOrDefault(-1L)
        fun vtt(ms: Long): String = String.format(
            java.util.Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000
        )
        var dansEvents = false
        for (brute in texte.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            val l = brute.trim()
            if (l.startsWith("[")) { dansEvents = l.equals("[Events]", ignoreCase = true); continue }
            if (!dansEvents) continue
            if (l.startsWith("Format:", ignoreCase = true)) {
                champs = l.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!l.startsWith("Dialogue:", ignoreCase = true)) continue
            val valeurs = l.substringAfter(':').split(',', limit = champs.size)
            val iDebut = champs.indexOf("start"); val iFin = champs.indexOf("end"); val iTexte = champs.indexOf("text")
            if (iDebut < 0 || iFin < 0 || iTexte < 0 || valeurs.size <= iTexte) continue
            val debut = heure(valeurs[iDebut]); val fin = heure(valeurs[iFin])
            if (debut < 0 || fin <= debut) continue
            val t = valeurs[iTexte]
                .replace(Regex("""\{[^}]*\}"""), "")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (t.isNotBlank()) lignes += Triple(debut, fin, t)
        }
        if (lignes.isEmpty()) return null
        Log.d("ConversionAss", "sous-titre ASS converti : ${lignes.size} réplique(s)")
        return buildString {
            append("WEBVTT\n\n")
            for ((d, f, t) in lignes.sortedBy { it.first }) {
                append(vtt(d)).append(" --> ").append(vtt(f)).append('\n').append(t).append("\n\n")
            }
        }
    }
}
