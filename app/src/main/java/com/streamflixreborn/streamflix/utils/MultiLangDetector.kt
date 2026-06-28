package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.providers.Provider

/**
 * 2026-06-24 (user "tu fais sur tous les providers VOD compatibles
 *   multi-langues") : helper centralisé pour détecter si une série
 *   supporte VF + VOSTFR (et lequel des deux est actif), commun à TOUS
 *   les providers qui implémentent un "compound wrapper pattern".
 *
 * Providers actuellement détectables (cf CLAUDE.md chantier 2026-06-23) :
 *  - AnimeSama : tvShow.id = "slug" / "slug@vf" / "slug@vostfr"
 *  - FrenchStream : season IDs "fs/@vf/..." / "fs/@vostfr/..."
 *  - Papadustream : season IDs "papa/.../@vf" / "papa/.../@vostfr"
 *  - FrenchManga : season IDs "manga/.../@vf" / "manga/.../@vostfr"
 *  - Franime : season IDs "franime/.../@vf" / "franime/.../@vostfr"
 *  - Wiflix : season IDs contiennent "blocfr" / "blocvostfr"
 *
 * Approche défensive : ne JAMAIS toucher le code des providers. On
 * INSPECTE seulement les IDs des saisons retournées par getTvShow et on
 * affiche/masque l'UI sans rien modifier côté provider.
 */
object MultiLangDetector {

    /** Langue détectée dans un id (season ou tvShow). null si pas détectable. */
    fun langOf(id: String): String? {
        if (id.isBlank()) return null
        // VOSTFR check FIRST (sinon "blocfr" matcherait "blocvf" via substring "fr")
        if (id.contains("@vostfr", ignoreCase = true)
            || id.contains("/vostfr/", ignoreCase = true)
            || id.endsWith("/vostfr", ignoreCase = true)
            || id.contains("blocvostfr", ignoreCase = true)) return "vostfr"
        if (id.contains("@vf", ignoreCase = true)
            || id.contains("/vf/", ignoreCase = true)
            || id.endsWith("/vf", ignoreCase = true)
            || id.contains("blocfr", ignoreCase = true)) return "vf"
        return null
    }

    /** Détection langue du tvShow.id (= utilisé par synopsis AnimeSama). */
    fun langOfTvShowId(tvShowId: String): String? {
        val after = tvShowId.substringAfter("@", "")
        return when {
            after.startsWith("vostfr") -> "vostfr"
            after.startsWith("vf") -> "vf"
            else -> null
        }
    }

    /**
     * Provider supporte-t-il le PATTERN compound wrapper ?
     * (= getTvShow renvoie soit slug@vf, soit slug@vostfr).
     * AnimeSama uniquement (DessinAnime Git retiré le 2026-06-28 : décision
     * user, le site dessinanime.cc ne supporte pas bien le wrapper VF/VOSTFR).
     */
    fun supportsTvShowIdRefetch(provider: Provider?): Boolean {
        val name = provider?.name ?: return false
        return name == "AnimeSama"
    }

    /**
     * Provider supporte-t-il le PATTERN season-id wrapper ?
     * (= getTvShow renvoie 2 saisons same number, IDs taggés blocfr/blocvostfr
     * ou /@vf/@vostfr ou /vf//vostfr).
     * On détecte automatiquement en regardant si AU MOINS 2 saisons ont des
     * langues différentes détectables (générique, robuste, marche pour tout
     * provider qui adopte la convention).
     */
    fun availableLangsFromSeasons(seasons: List<Season>): Set<String> {
        return seasons.mapNotNull { langOf(it.id) }.toSet()
    }

    /**
     * Filtre les saisons par langue. Garde les saisons sans lang détectée
     * (= pas un wrapper, on les laisse passer pour ne pas perdre du contenu).
     */
    fun filterSeasonsByLang(seasons: List<Season>, lang: String): List<Season> {
        return seasons.filter { s ->
            val detected = langOf(s.id)
            detected == null || detected == lang
        }
    }
}
