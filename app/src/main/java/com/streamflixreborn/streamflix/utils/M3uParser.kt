package com.streamflixreborn.streamflix.utils

import android.util.Log

/**
 * 2026-05-12 : parser M3U / M3U8 standard pour les playlists IPTV.
 *
 * Format attendu :
 * ```
 * #EXTM3U url-tvg="http://example.com/epg.xml"
 * #EXTINF:-1 tvg-id="tf1.fr" tvg-name="TF1" tvg-logo="http://...png" group-title="FR Généralistes",TF1
 * http://stream.url/tf1.m3u8
 * #EXTINF:-1 tvg-name="France 2" group-title="FR Généralistes",France 2
 * http://stream.url/france2.m3u8
 * ```
 *
 * Tolérant aux :
 * - lignes vides, BOM UTF-8 en tête, encodages variés
 * - attributs absents (tvg-logo et group-title sont facultatifs)
 * - guillemets simples ou doubles
 * - extra params custom (#EXTVLCOPT, #KODIPROP, etc. — ignorés)
 */
object M3uParser {
    private const val TAG = "M3uParser"

    // 2026-05-12 : regex pre-compilés. Sans ça, sur 176k entries × 3-4 attrs/entry,
    // on compile 700k+ regex en 2min. Avec pre-compile, le parse passe à <10s.
    private val ATTR_TVG_LOGO = Regex("""tvg-logo\s*=\s*(["'])([^"']*)\1""", RegexOption.IGNORE_CASE)
    private val ATTR_GROUP_TITLE = Regex("""group-title\s*=\s*(["'])([^"']*)\1""", RegexOption.IGNORE_CASE)
    private val ATTR_TVG_ID = Regex("""tvg-id\s*=\s*(["'])([^"']*)\1""", RegexOption.IGNORE_CASE)
    private val ATTR_TVG_NAME = Regex("""tvg-name\s*=\s*(["'])([^"']*)\1""", RegexOption.IGNORE_CASE)

    data class M3uChannel(
        /** Nom affiché (ex: "TF1"). Pris après la virgule dans #EXTINF. */
        val name: String,
        /** URL du stream (HTTP ou HTTPS, finit en .m3u8/.ts/.mp4 généralement). */
        val url: String,
        /** Logo URL (tvg-logo). Null si absent. */
        val logo: String?,
        /** Catégorie / groupe (group-title). Null si absent. */
        val group: String?,
        /** ID stable pour EPG (tvg-id). Null si absent. */
        val tvgId: String?,
        /** Options custom passées via #EXTVLCOPT (http-user-agent, http-referrer, etc.).
         *  Map vide si aucun KODIPROP/VLCOPT trouvé. */
        val options: Map<String, String> = emptyMap(),
    )

    /** Parse une playlist M3U depuis un String — wrapper pour parseStream. */
    fun parse(content: String): List<M3uChannel> {
        if (content.isBlank()) return emptyList()
        return parseStream(content.byteInputStream().bufferedReader())
    }

    /** 2026-05-12 (OOM 35MB+ M3U sur Chromecast) : parse en streaming ligne-par-ligne
     *  via BufferedReader. Permet de ne JAMAIS loader la M3U entière en RAM.
     *  Utilisé directement par MyIptvProvider.fetchM3u pour stream depuis OkHttp. */
    fun parseStream(reader: java.io.BufferedReader): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingTvgId: String? = null
        val pendingOptions = mutableMapOf<String, String>()
        var firstLineChecked = false

        reader.use {
            while (true) {
                val rawLine = it.readLine() ?: break
                // Strip BOM sur la 1re ligne
                val cleanedLine = if (!firstLineChecked) {
                    firstLineChecked = true
                    rawLine.trimStart('﻿', ' ', '\t')
                } else rawLine
                processLine(
                    cleanedLine, channels,
                    pendingNameRef = { pendingName },
                    setPendingName = { pendingName = it },
                    pendingLogoRef = { pendingLogo },
                    setPendingLogo = { pendingLogo = it },
                    pendingGroupRef = { pendingGroup },
                    setPendingGroup = { pendingGroup = it },
                    pendingTvgIdRef = { pendingTvgId },
                    setPendingTvgId = { pendingTvgId = it },
                    pendingOptions = pendingOptions,
                )
            }
        }
        Log.d(TAG, "Parsed ${channels.size} channels (streaming)")
        return channels
    }

    private inline fun processLine(
        rawLine: String,
        channels: MutableList<M3uChannel>,
        pendingNameRef: () -> String?,
        setPendingName: (String?) -> Unit,
        pendingLogoRef: () -> String?,
        setPendingLogo: (String?) -> Unit,
        pendingGroupRef: () -> String?,
        setPendingGroup: (String?) -> Unit,
        pendingTvgIdRef: () -> String?,
        setPendingTvgId: (String?) -> Unit,
        pendingOptions: MutableMap<String, String>,
    ) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        when {
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                pendingOptions.clear()
                setPendingLogo(null); setPendingGroup(null); setPendingTvgId(null)
                setPendingName(parseExtinf(line) { logo, group, tvgId ->
                    setPendingLogo(logo); setPendingGroup(group); setPendingTvgId(tvgId)
                })
            }
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                val raw = line.removePrefix("#EXTVLCOPT:").trim()
                val eq = raw.indexOf('=')
                if (eq > 0) pendingOptions[raw.substring(0, eq).trim()] = raw.substring(eq + 1).trim()
            }
            line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                val raw = line.removePrefix("#KODIPROP:").trim()
                val eq = raw.indexOf('=')
                if (eq > 0) pendingOptions[raw.substring(0, eq).trim()] = raw.substring(eq + 1).trim()
            }
            line.startsWith("#") -> Unit
            else -> {
                val name = pendingNameRef()
                if (name != null && (line.startsWith("http://", ignoreCase = true) ||
                        line.startsWith("https://", ignoreCase = true) ||
                        line.startsWith("rtmp://", ignoreCase = true) ||
                        line.startsWith("rtsp://", ignoreCase = true))
                ) {
                    channels += M3uChannel(
                        name = name,
                        url = line,
                        logo = pendingLogoRef(),
                        group = pendingGroupRef(),
                        tvgId = pendingTvgIdRef(),
                        options = pendingOptions.toMap(),
                    )
                }
                setPendingName(null); setPendingLogo(null); setPendingGroup(null); setPendingTvgId(null)
                pendingOptions.clear()
            }
        }
    }

    /** Parse une ligne `#EXTINF:-1 tvg-name="X" tvg-logo="Y" group-title="Z",DisplayName`
     *  → retourne le DisplayName, callback les attributs trouvés. */
    private inline fun parseExtinf(
        line: String,
        attrCallback: (logo: String?, group: String?, tvgId: String?) -> Unit,
    ): String {
        // #EXTINF:-1 [attrs],Display Name
        val afterColon = line.substringAfter(":", "")
        val commaIdx = afterColon.indexOf(',')
        val displayName: String
        val attrsPart: String
        if (commaIdx >= 0) {
            attrsPart = afterColon.substring(0, commaIdx)
            displayName = afterColon.substring(commaIdx + 1).trim()
        } else {
            attrsPart = afterColon
            displayName = ""
        }

        val logo = ATTR_TVG_LOGO.find(attrsPart)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
        val group = ATTR_GROUP_TITLE.find(attrsPart)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
        val tvgId = ATTR_TVG_ID.find(attrsPart)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
        attrCallback(logo, group, tvgId)
        return displayName.ifBlank {
            ATTR_TVG_NAME.find(attrsPart)?.groupValues?.get(2)?.takeIf { it.isNotBlank() } ?: "Sans nom"
        }
    }
}
