package com.streamflixreborn.streamflix.utils

import android.util.Log

/**
 * 2026-06-19 : Adrar TV — IPTV agrégateur communautaire (app.arnewapp525.com).
 *
 * Sources IPTV upstream identifiées via PCAPdroid :
 * 1. `http://151.80.18.177:86/<CHAIN_NAME>/index.m3u8`  (= HLS direct, server "Streamer 23.05")
 *    UA = "O" (1 char) obligatoire.
 * 2. `http://amir11.bounceme.net:8080/YZjwcwnLjh/QlXrgJTnED/<ID>` (= Restream TS direct)
 *    UA = "yy@" obligatoire.
 *
 * Brute-force 2026-06-19 sur serveur 1 → 8 chaînes France HD :
 *   TF1_HD, M6_HD, W9_HD, ARTE_HD, TMC, LCI_HD, NRJ_12, RTL9
 *
 * Brute-force 2026-06-19 sur serveur 2 → 80+ IDs Sport vivants (1640-1798).
 */
object AdrarTvService {

    private const val TAG = "AdrarTvService"
    private const val SERVER_HLS = "http://151.80.18.177:86"
    private const val SERVER_REST = "http://amir11.bounceme.net:8080/YZjwcwnLjh/QlXrgJTnED"
    private const val UA_HLS = "O"
    private const val UA_REST = "yy@"

    data class AdrarChannel(
        val name: String,
        val source: String,
        val ua: String,
        val group: String,
        val logo: String? = null,
    )

    /** Chaînes hardcodées confirmées vivantes au 2026-06-19. */
    private val HARDCODED_CHANNELS = buildList {
        // === France HD (serveur HLS direct 151.80.18.177:86) ===
        add(AdrarChannel("TF1 HD",   "$SERVER_HLS/TF1_HD/index.m3u8",  UA_HLS, "France",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tf1-fr.png"))
        add(AdrarChannel("TMC",      "$SERVER_HLS/TMC/index.m3u8",     UA_HLS, "France",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tmc-fr.png"))
        add(AdrarChannel("M6 HD",    "$SERVER_HLS/M6_HD/index.m3u8",   UA_HLS, "France",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/m6-fr.png"))
        add(AdrarChannel("W9 HD",    "$SERVER_HLS/W9_HD/index.m3u8",   UA_HLS, "France",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/w9-fr.png"))
        add(AdrarChannel("Arte HD",  "$SERVER_HLS/ARTE_HD/index.m3u8", UA_HLS, "France",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/arte-fr.png"))
        add(AdrarChannel("LCI HD",   "$SERVER_HLS/LCI_HD/index.m3u8",  UA_HLS, "France",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/lci-fr.png"))
        add(AdrarChannel("NRJ 12",   "$SERVER_HLS/NRJ_12/index.m3u8",  UA_HLS, "France",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/nrj-12-fr.png"))
        add(AdrarChannel("RTL9",     "$SERVER_HLS/RTL9/index.m3u8",    UA_HLS, "France",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/rtl-9-fr.png"))

        // === Sports (serveur amir11 — IDs vivants brute-forcés 2026-06-19) ===
        val sportIds = listOf(
            1640, 1641, 1642, 1643, 1644, 1645, 1647, 1648, 1649,
            1652, 1653, 1655, 1656, 1657, 1658, 1660,
            1664, 1665, 1666, 1667, 1669,
            1672, 1674, 1676, 1679, 1680, 1681, 1682, 1683, 1684,
            1686, 1687, 1688, 1690, 1691,
            1699, 1700, 1701, 1702, 1703, 1704, 1705, 1706, 1707,
            1709, 1711, 1712, 1719,
            1725, 1728, 1729, 1732, 1734, 1736, 1737,
            1741, 1744, 1746, 1747, 1748, 1749, 1750,
            1751, 1752, 1754, 1755, 1756, 1758, 1759,
            1763, 1765, 1771, 1775, 1776, 1785, 1789, 1791, 1792, 1794, 1798,
        )
        for (id in sportIds) {
            add(AdrarChannel(
                name = "Sport $id",
                source = "$SERVER_REST/$id",
                ua = UA_REST,
                group = "Sports",
                logo = null,
            ))
        }
    }

    private var cachedChannels: List<AdrarChannel>? = null
    private var cacheTimestamp = 0L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    @Volatile private var appContextRef: android.content.Context? = null
    fun installContext(ctx: android.content.Context) {
        appContextRef = ctx.applicationContext
    }

    suspend fun fetchChannels(): List<AdrarChannel> {
        val cached = cachedChannels
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }
        val channels = HARDCODED_CHANNELS
        cachedChannels = channels
        cacheTimestamp = System.currentTimeMillis()
        Log.d(TAG, "fetchChannels: ${channels.size} channels (${channels.count { it.group == "France" }} France, ${channels.count { it.group == "Sports" }} Sports)")
        return channels
    }

    suspend fun getUrlForChannel(channelKey: String): Pair<String, Map<String, String>>? {
        val ch = fetchChannels().firstOrNull { keyOf(it) == channelKey } ?: return null
        return ch.source to mapOf(
            "User-Agent" to ch.ua,
            "Accept-Encoding" to "gzip",
        )
    }

    fun keyOf(ch: AdrarChannel): String {
        return "${ch.group}__${ch.name.replace(" ", "_").replace("/", "").lowercase()}"
    }

    suspend fun getChannelsByGroup(group: String): List<AdrarChannel> {
        return fetchChannels().filter { it.group.equals(group, ignoreCase = true) }
    }

    suspend fun getGroups(): List<String> {
        return fetchChannels().map { it.group }.distinct()
    }

    var selectedGroup: String
        get() {
            val ctx = appContextRef
                ?: com.streamflixreborn.streamflix.StreamFlixApp.instance
            return ctx.getSharedPreferences("adar_prefs", android.content.Context.MODE_PRIVATE)
                .getString("selected_group", "") ?: ""
        }
        set(value) {
            val ctx = appContextRef
                ?: com.streamflixreborn.streamflix.StreamFlixApp.instance
            ctx.getSharedPreferences("adar_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("selected_group", value).apply()
        }

    fun clearCache() {
        cachedChannels = null
        cacheTimestamp = 0
    }
}
