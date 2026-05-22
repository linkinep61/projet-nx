package com.streamflixreborn.streamflix.utils

/**
 * Détecte la langue/version réellement disponible (VF / VOSTFR / VO) à partir
 * des métadonnées DÉJÀ fournies par chaque provider (titre, qualité, synopsis,
 * libellés de saisons).
 *
 * But : afficher sur la fiche détail, AVANT de lancer, ce que l'utilisateur va
 * vraiment entendre. Certains providers (Wiflix…) précisent "VOSTFR" mais
 * l'info n'était pas remontée chez nous → on lançait en croyant que c'était du
 * français alors que ce n'est que du VOSTFR.
 *
 * Règle de confiance (anti faux-positifs) :
 *  - champs "contrôlés" (qualité, libellés de saison) : vocabulaire court fiable
 *    → on fait confiance aux tokens courts (VF, FRENCH, MULTI, VOST, …).
 *  - titre : semi-fiable → on n'y détecte QUE des tokens non ambigus
 *    (VOST*, VFF/VFQ/VF, TRUEFRENCH). On NE détecte PAS "FRENCH"/"MULTI" dans le
 *    titre car ils peuvent faire partie d'un vrai nom de film (ex: "The French
 *    Dispatch").
 *  - texte libre (synopsis) : on ne fait confiance qu'aux tokens forts
 *    (VOSTFR, TRUEFRENCH, "version française", "version originale sous-titrée").
 */
object LanguageTag {

    enum class Version { VF, VOSTFR, VO }

    // Tokens forts, sans ambiguïté — sûrs même dans du texte libre (synopsis).
    private val STRONG_VOSTFR =
        Regex("(?i)\\bvostfr\\b|\\bvostf\\b|\\bvo\\s?st\\s?fr\\b|version\\s+originale\\s+sous[ -]?titr")
    private val STRONG_VF =
        Regex("(?i)\\btruefrench\\b|\\bvff\\b|\\bvfq\\b|\\bvfi\\b|version\\s+fran[çc]aise")

    // Tokens courts — fiables uniquement dans les champs contrôlés (qualité, saisons).
    private val SHORT_VOSTFR = Regex("(?i)\\bvost(?:fr|f|a)?\\b|\\bvo\\s?st\\b|sous[ -]?titr")
    private val SHORT_VF = Regex("(?i)\\bvf[fiq]?\\b|\\btruefrench\\b|\\bfrench\\b|\\bmulti\\b")
    private val SHORT_VO = Regex("(?i)\\bvo\\b")

    // Tokens autorisés dans le titre (sans "french"/"multi" qui peuvent être un vrai nom).
    private val TITLE_VF = Regex("(?i)\\bvf[fiq]?\\b|\\btruefrench\\b")

    /** Détecte la version. Priorité VF > VOSTFR > VO (VF dispo = bon cas pour l'utilisateur FR). */
    fun detect(
        title: String? = null,
        quality: String? = null,
        overview: String? = null,
        seasonLabels: List<String> = emptyList(),
        version: String? = null,
    ): Version? {
        // 1) Source AUTORITAIRE fournie explicitement par le provider (ex: champ
        //    "Version: VOSTFR" de Wiflix). Elle prime sur toute heuristique :
        //    un film peut s'appeler "... French" tout en étant en VOSTFR.
        if (!version.isNullOrBlank()) {
            val vVf = SHORT_VF.containsMatchIn(version)
            val vVostfr = SHORT_VOSTFR.containsMatchIn(version)
            val vVo = SHORT_VO.containsMatchIn(version.replace(SHORT_VOSTFR, " "))
            when {
                vVf -> return Version.VF
                vVostfr -> return Version.VOSTFR
                vVo -> return Version.VO
            }
        }

        val controlled = (listOfNotNull(quality) + seasonLabels).joinToString("  ")
        val titleStr = title.orEmpty()
        val free = overview.orEmpty()

        val hasVf = SHORT_VF.containsMatchIn(controlled) ||
                TITLE_VF.containsMatchIn(titleStr) ||
                STRONG_VF.containsMatchIn(free)

        val hasVostfr = SHORT_VOSTFR.containsMatchIn(controlled) ||
                SHORT_VOSTFR.containsMatchIn(titleStr) ||
                STRONG_VOSTFR.containsMatchIn(free)

        // VO seulement depuis les champs contrôlés, jamais confondu avec VOST.
        val controlledNoVost = controlled.replace(SHORT_VOSTFR, " ")
        val hasVo = SHORT_VO.containsMatchIn(controlledNoVost)

        return when {
            hasVf -> Version.VF
            hasVostfr -> Version.VOSTFR
            hasVo -> Version.VO
            else -> null
        }
    }

    fun label(version: Version?): String? = when (version) {
        Version.VF -> "VF"
        Version.VOSTFR -> "VOSTFR"
        Version.VO -> "VO"
        null -> null
    }

    /**
     * Préfixe le synopsis par la version détectée, ex : "VOSTFR — <synopsis>".
     * C'est l'usage voulu par le user : voir la langue ÉCRITE au niveau de la
     * synopsis (comme Wiflix), pas dans la ligne d'infos. Renvoie le synopsis tel
     * quel si aucune version n'est détectée, ou juste la version s'il n'y a pas de
     * synopsis, ou null si rien.
     */
    fun prefixOverview(
        overview: String?,
        title: String? = null,
        quality: String? = null,
        seasonLabels: List<String> = emptyList(),
        version: String? = null,
    ): String? {
        val tag = label(detect(title, quality, overview, seasonLabels, version))
        val ov = overview?.trim().orEmpty()
        return when {
            tag != null && ov.isNotBlank() -> "$tag — $ov"
            tag != null -> tag
            ov.isNotBlank() -> ov
            else -> null
        }
    }
}
