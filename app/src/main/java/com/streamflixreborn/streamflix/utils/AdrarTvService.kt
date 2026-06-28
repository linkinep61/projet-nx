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

        // === Serveur amir11 (restream TS) — IDs identifiés via extraction de frames + reconnaissance de logo (2026-06-22) ===
        // Les IDs étaient avant TOUS étiquetés "Sport XXXX" et mis en groupe "Sports", alors qu'en réalité
        // c'est un méli-mélo de chaînes TNT France, sport, jeunesse, doc, ciné, etc. On les classe correctement
        // par groupe et on donne un nom humain (vrai logo identifié sur frame ffmpeg + lecture visuelle).
        // - Le LOGO en haut-droite de chaque flux a été lu sur 1 frame extraite à t=5s.
        // - Pour les IDs où aucun logo n'apparaissait (pub, EPG, credits, film sans bug), on garde "Adrar N°XXXX"
        //   et on les met dans le groupe "Autres" — comme ça la section Sports reste propre = uniquement
        //   de vraies chaînes sport.
        // - 1684 et 1690 = streams morts (pas de vidéo) → exclus.
        data class AdrarMap(val id: Int, val name: String, val group: String, val logo: String? = null)
        val mappings = listOf(
            // === France TNT (chaînes France généralistes) ===
            AdrarMap(1648, "France TV (1)",       "France"),
            AdrarMap(1649, "France TV (2)",       "France"),
            AdrarMap(1655, "France TV Direct",    "France"),
            AdrarMap(1656, "TFX",                 "France",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tfx-fr.png"),
            AdrarMap(1660, "France 2",            "France",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/france-2-fr.png"),
            AdrarMap(1719, "TF1 Séries Films",    "France",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tf1-series-films-fr.png"),
            AdrarMap(1725, "C Star",              "France",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/cstar-fr.png"),
            AdrarMap(1729, "Paris Première",      "France",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/paris-premiere-fr.png"),
            // IDs en doublon avec SERVER_HLS (W9, Arte, TMC, RTL9) — non ré-ajoutés pour éviter les dupes

            // === Sports (vrais sports — beIN, RMC Sport, L'Équipe, Ligue 1+) ===
            AdrarMap(1664, "RMC Sport",           "Sports",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/rmc-sport-1-fr.png"),
            AdrarMap(1665, "beIN Sports 1",       "Sports",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/bein-sports-1-fr.png"),
            AdrarMap(1666, "beIN Sports 2",       "Sports",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/bein-sports-2-fr.png"),
            AdrarMap(1667, "beIN Sports 3",       "Sports",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/bein-sports-3-fr.png"),
            AdrarMap(1669, "beIN Sports 5",       "Sports"),
            AdrarMap(1672, "beIN Sports 9 (BMAX)","Sports"),
            AdrarMap(1674, "beIN Sports 6",       "Sports"),
            AdrarMap(1676, "L'Équipe (1)",        "Sports",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/lequipe-tv-fr.png"),
            AdrarMap(1681, "L'Équipe (2)",        "Sports",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/lequipe-tv-fr.png"),
            AdrarMap(1789, "Ligue 1+",            "Sports"),
            AdrarMap(1791, "Ligue 1+ Multi 7",    "Sports"),
            AdrarMap(1798, "Ligue 1+ Multi 6",    "Sports"),

            // === Jeunesse ===
            AdrarMap(1652, "Okoo (1)",            "Jeunesse"),
            AdrarMap(1653, "Okoo (2)",            "Jeunesse"),
            AdrarMap(1688, "Discovery Kids",      "Jeunesse"),
            AdrarMap(1728, "Cartoon Network",     "Jeunesse",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/cartoon-network-fr.png"),
            AdrarMap(1732, "Nickelodeon",         "Jeunesse",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/nickelodeon-fr.png"),
            AdrarMap(1736, "Piwi+",               "Jeunesse",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/piwi-plus-fr.png"),
            AdrarMap(1741, "Cartoonito",          "Jeunesse",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/cartoonito-fr.png"),
            AdrarMap(1744, "Gulli",               "Jeunesse",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/gulli-fr.png"),

            // === Découverte / Documentaire / Histoire ===
            AdrarMap(1703, "Chasse & Pêche (1)",  "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/chasse-et-peche-fr.png"),
            AdrarMap(1746, "Investigation Discovery", "Découverte"),
            AdrarMap(1747, "Crime District",      "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/crime-district-fr.png"),
            AdrarMap(1752, "National Geographic", "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/national-geographic-fr.png"),
            AdrarMap(1754, "Animaux",             "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/animaux-fr.png"),
            AdrarMap(1755, "Chasse & Pêche (2)",  "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/chasse-et-peche-fr.png"),
            AdrarMap(1756, "Seasons",             "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/seasons-fr.png"),
            AdrarMap(1758, "Toute l'Histoire",    "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/toute-l-histoire-fr.png"),
            AdrarMap(1759, "Histoire TV",         "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/histoire-tv-fr.png"),
            AdrarMap(1763, "Discovery Channel",   "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/discovery-channel-fr.png"),
            AdrarMap(1765, "Trek",                "Découverte",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/trek-fr.png"),

            // === Cinéma / Divertissement ===
            AdrarMap(1704, "Action",              "Cinéma",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/action-fr.png"),
            AdrarMap(1707, "Comédie+",            "Cinéma",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/comedie-plus-fr.png"),
            AdrarMap(1737, "J-One",               "Cinéma",
                logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/j-one-fr.png"),
        )
        // Tous les IDs vivants au 2026-06-19 (extrait du brute-force initial)
        val allLiveIds = listOf(
            1640, 1641, 1642, 1643, 1644, 1645, 1647, 1648, 1649,
            1652, 1653, 1655, 1656, 1657, 1658, 1660,
            1664, 1665, 1666, 1667, 1669,
            1672, 1674, 1676, 1679, 1680, 1681, 1682, 1683,
            1686, 1687, 1688, 1691,
            1699, 1700, 1701, 1702, 1703, 1704, 1705, 1706, 1707,
            1709, 1711, 1712, 1719,
            1725, 1728, 1729, 1732, 1734, 1736, 1737,
            1741, 1744, 1746, 1747, 1748, 1749, 1750,
            1751, 1752, 1754, 1755, 1756, 1758, 1759,
            1763, 1765, 1771, 1775, 1776, 1785, 1789, 1791, 1792, 1794, 1798,
        )
        // 1684 et 1690 sont morts (pas de stream vidéo) → exclus de la liste.
        val mappedIds = mappings.map { it.id }.toSet()
        for (m in mappings) {
            add(AdrarChannel(
                name = m.name,
                source = "$SERVER_REST/${m.id}",
                ua = UA_REST,
                group = m.group,
                logo = m.logo,
            ))
        }
        // Les IDs non identifiés (pub, EPG, credits, films sans logo) → groupe "Autres"
        // avec un nom générique "Adrar N°XXXX" — accessibles mais hors des vraies catégories.
        for (id in allLiveIds) {
            if (id in mappedIds) continue
            add(AdrarChannel(
                name = "Adrar N°$id",
                source = "$SERVER_REST/$id",
                ua = UA_REST,
                group = "Autres",
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
        Log.d(TAG, "fetchChannels: ${channels.size} channels — " +
            channels.groupBy { it.group }.mapValues { it.value.size }.entries.joinToString { "${it.key}=${it.value}" })
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
