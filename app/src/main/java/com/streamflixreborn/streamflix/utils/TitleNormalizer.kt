package com.streamflixreborn.streamflix.utils

/**
 * 2026-05-05 : Utilitaire centralisé de normalisation de titres.
 *
 * Contexte : on a découvert deux bugs équivalents dans des providers
 * différents (VoirDrama avec « Yumi's Cells » → apostrophe typographique
 * U+2019 encodée en %E2%80%99 → 0 résultats Dramacool ; Movix avec
 * « Sonic the Hedgehog 3 [Version française] » → 0 match TMDB).
 *
 * Pour éviter que ce genre de bug réapparaisse à chaque provider, on
 * centralise les fonctions de nettoyage dans cet objet partagé.
 *
 * - [stripUnicodeArtifacts] : remplace les caractères Unicode "joliesques"
 *   (apostrophes courbes, guillemets typographiques, em/en dashes, ellipsis)
 *   par leurs équivalents ASCII. À utiliser avant TOUT envoi à une API
 *   externe (TMDB, recherche d'un site scrappé, etc.).
 *
 * - [cleanForTmdbSearch] : version plus agressive qui retire en plus les
 *   annotations style « Saison 3 », « S01E01 », « (2024) », « VOSTFR »,
 *   « [Version française] » etc. qui font foirer les matchs TMDB.
 */
object TitleNormalizer {

    /**
     * Remplace les caractères typographiques Unicode courants par leur
     * équivalent ASCII. Sûr à appeler sur n'importe quelle string : si
     * elle est déjà clean, l'output est identique.
     *
     * Couvre :
     *  - U+2019 ’ et U+2018 ‘ → '
     *  - U+201C “ et U+201D ” → "
     *  - U+2013 – et U+2014 — → -
     *  - U+2026 … → " " (espace, car souvent suivi d'un mot)
     *  - U+00A0 (espace insécable) → espace normal
     */
    fun stripUnicodeArtifacts(raw: String): String {
        if (raw.isEmpty()) return raw
        return raw
            .replace('’', '\'').replace('‘', '\'')
            .replace('“', '"').replace('”', '"')
            .replace('–', '-').replace('—', '-')
            .replace('…', ' ')
            .replace(' ', ' ')
    }

    /**
     * Normalisation complète pour les recherches TMDB. Combine
     * [stripUnicodeArtifacts] avec le retrait des annotations qui polluent
     * les matchs (saison, épisode, année, versions linguistiques, marqueurs
     * entre crochets).
     *
     * Exemples :
     *  - « Sonic the Hedgehog 3 [Version française] » → « Sonic the Hedgehog 3 »
     *  - « Yumi's Cells - Saison 3 (2026) » → « Yumi's Cells »
     *  - « Squid Game S01E01 » → « Squid Game »
     */
    fun cleanForTmdbSearch(raw: String): String {
        if (raw.isBlank()) return raw
        return stripUnicodeArtifacts(raw)
            // Annotations entre crochets : [Version française], [VF], [HD], etc.
            .replace(Regex("""\s*\[[^\]]*\]"""), "")
            // Annotations linguistiques entre parenthèses
            .replace(
                Regex(
                    """\s*\((?:VF|VOSTFR|VOST|VO|Dub|Sub|HD|FR|TrueFrench|VFF|VFQ)\)""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            // Année entre parenthèses : (2024)
            .replace(Regex("""\s*\(\d{4}\)\s*"""), " ")
            // Saison N (range ou simple) : "Saison 1-3", "Saison 2"
            .replace(Regex("""\s*[-–]?\s*Saison\s*\d+(?:\s*[-–]\s*\d+)?.*$""", RegexOption.IGNORE_CASE), "")
            // 2026-05-05 v2 : range de saisons style "S1-S2" / "S01-S03" (Moviebox
            // colle ça en suffixe pour les séries multi-saisons : "Wednesday S1-S2")
            .replace(Regex("""\s*[-–]?\s*[Ss]\d+\s*[-–]\s*[Ss]\d+.*$"""), "")
            // SnEm
            .replace(Regex("""\s*[-–]?\s*[Ss]\d+\s*[Ee]\d+.*$"""), "")
            // Saison seule en fin : "S1", "S01", "S2"
            .replace(Regex("""\s+[Ss]\d+\s*$"""), "")
            // Épisode N et tout ce qui suit
            .replace(Regex("""\s*[-–]?\s*[ÉéEe]pisode\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            // Suffixes linguistiques en fin de string : VOSTFR, VF, etc.
            .replace(
                Regex("""\s+(VOSTFR|VOST|VF|VO|TrueFrench|VFF|VFQ|FR)\b.*$""", RegexOption.IGNORE_CASE),
                ""
            )
            // Espaces multiples → simple
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
