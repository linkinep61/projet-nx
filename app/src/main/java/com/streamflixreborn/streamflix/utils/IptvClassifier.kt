package com.streamflixreborn.streamflix.utils

/**
 * 2026-05-12 (user "il faut peut-être que ça télécharge le contenu dans
 * une catégorie film et série et TV comme un IPTV") : classifier qui
 * détecte automatiquement le type d'une entrée M3U.
 *
 * Heuristique (ordre de priorité) :
 * 1. **URL extension** : `.m3u8`/`.ts`/`.flv` → LIVE, `.mp4`/`.mkv`/`.avi`/`.mov` → VOD
 * 2. **group-title keywords** : "live", "tv", "iptv" → LIVE ; "film", "movie", "vod" → MOVIE ; "serie", "show" → SERIES
 * 3. **Pattern SXXEXX dans le nom** : "Show S01E01", "Show 1x05", "Show Episode 12" → SERIES
 * 4. **Suffixe année** : "Title (2024)" → MOVIE
 * 5. **Default** : LIVE (la plupart des playlists IPTV historiques sont live)
 */
object IptvClassifier {

    enum class ContentType {
        LIVE,    // Stream live TV (HLS, DASH, RTMP)
        MOVIE,   // VOD film (single mp4/mkv)
        SERIES,  // VOD série, 1 épisode (groupé par show name côté provider)
    }

    private val LIVE_EXTENSIONS = setOf("m3u8", "ts", "flv", "mpd")
    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "webm")

    private val LIVE_KEYWORDS = setOf("live", "tv ", "iptv", "channels", "chaînes", "chaine", "channel")
    // 2026-05-13 (user "PTV se trompé dans la catégorie films et séries — c'est
    // des chaînes qu'il a mis fillme") : retiré "1080p", "4k", " hd " des
    // MOVIE_KEYWORDS. Ce sont des marqueurs de QUALITÉ, pas de type. Beaucoup
    // de chaînes live (ARABE 4K, FRANCE 4K, BEIN SPORT 4K…) avaient leur group
    // taggé "4K" et étaient donc mal classifiées comme films.
    private val MOVIE_KEYWORDS = setOf("film", "movie", "vod ", "cinéma", "cinema")
    // 2026-05-13 : élargi les SERIES_KEYWORDS pour mieux détecter les bouquets
    // de séries — ajout de "tv shows", "shows", "séries tv", "drama", "novela"
    // (telenovela). "show " seul est trop large (chaîne "show TV") → on garde
    // mais via les patterns plus stricts.
    private val SERIES_KEYWORDS = setOf("serie", "série", "séries", "series", "saison", "season", "tv shows", "tv show", "drama ", "novela", "telenovela")

    /** Pattern SXXEXX ou 1x05 ou "Episode X" — indique une série épisode. */
    private val SERIES_PATTERN = Regex(
        """(\b[sS]\d{1,2}\s*[eE]\d{1,3}\b)|(\b\d{1,2}\s*x\s*\d{1,3}\b)|(\bsaison\s+\d+\b)|(\bseason\s+\d+\b)|(\bépisode\s+\d+\b)|(\bepisode\s+\d+\b)""",
        RegexOption.IGNORE_CASE,
    )

    /** Pattern (YYYY) en fin de nom — indique un film. */
    private val YEAR_PATTERN = Regex("""\((19|20)\d{2}\)\s*$""")

    fun classify(channel: M3uParser.M3uChannel): ContentType {
        // 0a. 2026-05-12 : tag explicite Xtream API → fiable à 100% (classification serveur)
        when (channel.options["xtream-type"]) {
            "live" -> return ContentType.LIVE
            "movie" -> return ContentType.MOVIE
            "series" -> return ContentType.SERIES
        }

        val urlLower = channel.url.lowercase().substringBefore('?')
        val groupLower = (channel.group ?: "").lowercase()

        // 0. **Xtream Codes URL path** — signal le plus fiable. Format standard :
        //   `/live/USER/PASS/CHANID.ts` → LIVE
        //   `/movie/USER/PASS/MOVID.mp4` → MOVIE
        //   `/series/USER/PASS/SHOWID/SEASONID/EPID.mp4` → SERIES
        //   Détecté avant tout autre signal.
        when {
            urlLower.contains("/series/") -> return ContentType.SERIES
            urlLower.contains("/movie/") || urlLower.contains("/vod/") -> return ContentType.MOVIE
            urlLower.contains("/live/") -> return ContentType.LIVE
        }

        // 1. URL extension a priorité (après Xtream path)
        val ext = urlLower.substringAfterLast('.', "")
        if (ext in LIVE_EXTENSIONS) {
            // Live confirmé sauf si le group-title dit explicitement VOD
            if (SERIES_KEYWORDS.any { groupLower.contains(it) }) return ContentType.SERIES
            if (MOVIE_KEYWORDS.any { groupLower.contains(it) }) return ContentType.MOVIE
            return ContentType.LIVE
        }
        if (ext in VOD_EXTENSIONS) {
            // VOD confirmé — film ou série ?
            if (SERIES_PATTERN.containsMatchIn(channel.name)) return ContentType.SERIES
            if (SERIES_KEYWORDS.any { groupLower.contains(it) }) return ContentType.SERIES
            return ContentType.MOVIE
        }

        // 2. group-title si extension ambiguë
        if (SERIES_KEYWORDS.any { groupLower.contains(it) }) return ContentType.SERIES
        if (MOVIE_KEYWORDS.any { groupLower.contains(it) }) return ContentType.MOVIE
        if (LIVE_KEYWORDS.any { groupLower.contains(it) }) return ContentType.LIVE

        // 3. Pattern dans le nom
        if (SERIES_PATTERN.containsMatchIn(channel.name)) return ContentType.SERIES
        if (YEAR_PATTERN.containsMatchIn(channel.name)) return ContentType.MOVIE

        // 4. Default
        return ContentType.LIVE
    }

    /**
     * Extrait le nom de la série d'un nom d'épisode.
     * "Friends S01E01 - The Pilot" → "Friends"
     * "Game of Thrones 1x01" → "Game of Thrones"
     * "Show Saison 2 Episode 3" → "Show"
     */
    // 2026-05-12 : regex pre-compilés pour extractShowName — appelé 11k+ fois
    private val SHOW_NAME_PATTERNS = listOf(
        Regex("""\s*[sS]\d{1,2}\s*[eE]\d{1,3}.*""", RegexOption.IGNORE_CASE),
        Regex("""\s*\d{1,2}\s*x\s*\d{1,3}.*"""),
        Regex("""\s*saison\s+\d+.*""", RegexOption.IGNORE_CASE),
        Regex("""\s*season\s+\d+.*""", RegexOption.IGNORE_CASE),
        Regex("""\s*épisode\s+\d+.*""", RegexOption.IGNORE_CASE),
        Regex("""\s*episode\s+\d+.*""", RegexOption.IGNORE_CASE),
        Regex("""\s*-\s*$"""),
    )

    fun extractShowName(episodeName: String): String {
        var clean = episodeName
        for (pattern in SHOW_NAME_PATTERNS) clean = clean.replace(pattern, "")
        clean = clean.trim()
        return clean.ifBlank { episodeName }
    }
}
