package com.streamflixreborn.streamflix.utils

import android.util.Base64

/**
 * 2026-06-10 (user "tu vas pouvoir alléger le code avec la nouvelle fonction") :
 *  utilitaires partagés entre les providers IPTV qui parsent les playlists
 *  3box TV / rebrand.ly / Google Sheets JSON-per-line.
 *
 *  Avant : BoxXtemusProvider et WorldLiveTvProvider avaient chacun leur copie
 *  privée de `decodeRebrandlyUrl()` + `extractFirstJsonObject()`. Maintenant
 *  les 2 utilisent ces fonctions communes.
 */
object PlaylistParserHelpers {

    /** Décode une URL rebrand.ly `https://rebrand.ly/u3?q=<base64>` en sa
     *  cible. Les q-values 3box TV sont base64 split par `?` littéral
     *  (chaque partie est base64 standard, rejoint avec `?`). */
    fun decodeRebrandlyUrl(url: String): String? {
        val qParam = Regex("[?&]q=([^&]+)").find(url)?.groupValues?.get(1) ?: return null
        return try {
            val parts = qParam.split("?")
            val decoded = parts.map { part ->
                val padded = part + "=".repeat((4 - part.length % 4) % 4)
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            }
            val result = decoded.joinToString("?")
            if (result.startsWith("http")) result else null
        } catch (_: Exception) { null }
    }

    /** Extrait le premier objet JSON `{...}` complet d'une string (gère les
     *  accolades imbriquées et les strings échappées). Retourne null si pas
     *  d'objet bien-formé. */
    fun extractFirstJsonObject(s: String): String? {
        if (!s.startsWith("{")) return null
        var depth = 0; var inString = false; var escape = false
        for (i in s.indices) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            else if (c == '}') { depth--; if (depth == 0) return s.substring(0, i + 1) }
        }
        return null
    }
}
