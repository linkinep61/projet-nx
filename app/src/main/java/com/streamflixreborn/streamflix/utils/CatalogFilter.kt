package com.streamflixreborn.streamflix.utils

import androidx.preference.PreferenceManager
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * 2026-05-20 — Filtre de catalogue choisi par l'utilisateur, pour les providers
 * dont le home est basé sur TMDB Discover (Cloudstream pour commencer).
 *
 * Contexte (user) : sur Cloudstream le home n'affichait QUE du contenu d'origine
 * française (filtre `withOriginalLanguage="fr"` figé), alors que l'utilisateur
 * voulait surtout de l'audio français (VF) — donc aussi des films/séries
 * étrangers doublés. TMDB ne sait pas filtrer « a une VF », mais on peut au moins
 * laisser l'utilisateur choisir la langue/contenu du catalogue.
 *
 * Le choix est mémorisé PAR PROVIDER (clé `pref_catalog_filter_<provider>`).
 */
object CatalogFilter {

    /**
     * Mode = un libellé + la langue d'origine TMDB à appliquer (null = pas de
     * filtre de langue → contenu populaire international, majoritairement dispo
     * en VF sur le marché FR).
     */
    enum class Mode(
        val pref: String,
        val label: String,
        val originalLanguage: String?,
        /** Filtres TMDB Discover ajoutés pour les FILMS / les SÉRIES (univers d'animation). */
        val filtresFilms: Map<String, String> = emptyMap(),
        val filtresSeries: Map<String, String> = emptyMap(),
    ) {
        POPULAR_INTL("popular_intl", "Monde — FR", null),
        ORIGIN_FR("origin_fr", "Productions françaises", "fr"),
        US_EN("us_en", "Anglophone / US-UK", "en"),
        // 2026-07-11 (user) : « Anime / Japon » retiré — on a enlevé tout le contenu animé de
        //   Movix ET Cloudstream, donc ce filtre ne renvoyait plus rien de cohérent. Un ancien
        //   choix "anime_ja" sauvegardé retombe automatiquement sur DEFAULT via fromPref().
        KO("ko", "Coréen (K-drama)", "ko"),
        ES("es", "Espagnol / hispanique", "es"),
        HI("hi", "Indien / Bollywood", "hi"),
        ZH("zh", "Chinois", "zh"),
        IT("it", "Italien", "it"),
        DE("de", "Allemand", "de"),
        TR("tr", "Turc", "tr"),

        // 2026-11-01 (user) : « univers » de DessinAnime — on choisit ce qu'on a envie de
        //   regarder, et l'accueil, les Films et les Séries ne montrent plus que ça.
        //   Identifiants TMDB vérifiés : mot-clé « anime » 210024 ; studios Walt Disney
        //   Pictures 2, Pixar 3, Walt Disney Animation Studios 6125, DreamWorks Animation 521,
        //   Studio Ghibli 10342, Nickelodeon Movies 2348 ; chaînes Disney Channel 54,
        //   Disney Junior 281, Disney+ 2739, Cartoon Network 56, Nickelodeon 13.
        //   Japonais = mot-clé « anime » (pas la langue : beaucoup d'animés ont une VF).
        ANIM_TOUT("anim_tout", "Toute l'animation", null),
        ANIM_JA("anim_ja", "Animés japonais", null,
            mapOf("with_keywords" to "210024"), mapOf("with_keywords" to "210024")),
        ANIM_DESSINS("anim_dessins", "Dessins animés", null,
            mapOf("without_keywords" to "210024"), mapOf("without_keywords" to "210024")),
        ANIM_DISNEY("anim_disney", "Disney / Pixar", null,
            mapOf("with_companies" to "2|3|6125"), mapOf("with_networks" to "54|281|2739")),
        ANIM_DREAMWORKS("anim_dreamworks", "DreamWorks", null,
            mapOf("with_companies" to "521"), mapOf("with_companies" to "521")),
        ANIM_GHIBLI("anim_ghibli", "Studio Ghibli", null,
            mapOf("with_companies" to "10342"), mapOf("with_companies" to "10342")),
        ANIM_CARTOON("anim_cartoon", "Cartoon Network / Nickelodeon", null,
            mapOf("with_companies" to "2348"), mapOf("with_networks" to "56|13"));

        val animation: Boolean get() = pref.startsWith("anim_")

        companion object {
            /** Défaut = comportement historique (origine FR) pour ne rien changer
             *  par surprise ; l'utilisateur bascule lui-même via le bouton filtre. */
            val DEFAULT = ORIGIN_FR
            fun fromPref(p: String?): Mode = entries.find { it.pref == p } ?: DEFAULT
        }
    }

    /** Providers compatibles (home basé sur TMDB Discover, filtrable par langue).
     *  Cloudstream + Movix + Tmdb. Nakios EXCLU : c'est une source/backup, pas un
     *  provider à browser (il suit les autres). */
    fun isSupported(providerName: String?): Boolean =
        providerName == "Cloudstream" ||
        providerName == "Movix" ||
        providerName == "NetMirror" ||
        providerName?.startsWith("TMDb") == true ||
        providerName == "DessinAnime"

    /** Choix proposés par le bouton : univers d'animation pour DessinAnime, langues sinon. */
    fun modesFor(providerName: String?): List<Mode> =
        if (providerName == "DessinAnime") Mode.entries.filter { it.animation }
        else Mode.entries.filter { !it.animation }

    /** Défaut PAR provider pour ne rien changer par surprise :
     *  - Cloudstream était FR-only → ORIGIN_FR (comportement historique)
     *  - Tmdb / Movix montraient du contenu mondial → POPULAR_INTL (pas de restriction) */
    fun defaultFor(providerName: String): Mode = when (providerName) {
        "Cloudstream" -> Mode.ORIGIN_FR
        "DessinAnime" -> Mode.ANIM_TOUT
        else -> Mode.POPULAR_INTL
    }

    private fun key(providerName: String) = "pref_catalog_filter_$providerName"

    fun get(providerName: String): Mode = try {
        val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
        val saved = prefs.getString(key(providerName), null)
        // Un choix qui n'appartient pas à ce fournisseur (ancienne valeur, synchro…) → défaut.
        val m = if (saved == null) defaultFor(providerName) else Mode.fromPref(saved)
        if (m in modesFor(providerName)) m else defaultFor(providerName)
    } catch (_: Exception) {
        defaultFor(providerName)
    }

    fun set(providerName: String, mode: Mode) {
        try {
            PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
                .edit().putString(key(providerName), mode.pref).apply()
        } catch (_: Exception) {
        }
    }

    /** Langue d'origine TMDB pour le mode courant du provider (null = aucune). */
    fun originalLanguage(providerName: String): String? = get(providerName).originalLanguage
}
