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
    enum class Mode(val pref: String, val label: String, val originalLanguage: String?) {
        POPULAR_INTL("popular_intl", "Monde — tout (souvent VF)", null),
        ORIGIN_FR("origin_fr", "Productions françaises", "fr"),
        US_EN("us_en", "Anglophone / US-UK", "en"),
        ANIME_JA("anime_ja", "Anime / Japon", "ja"),
        KO("ko", "Coréen (K-drama)", "ko"),
        ES("es", "Espagnol / hispanique", "es"),
        HI("hi", "Indien / Bollywood", "hi"),
        ZH("zh", "Chinois", "zh"),
        IT("it", "Italien", "it"),
        DE("de", "Allemand", "de"),
        TR("tr", "Turc", "tr");

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
        providerName?.startsWith("TMDb") == true

    /** Défaut PAR provider pour ne rien changer par surprise :
     *  - Cloudstream était FR-only → ORIGIN_FR (comportement historique)
     *  - Tmdb / Movix montraient du contenu mondial → POPULAR_INTL (pas de restriction) */
    fun defaultFor(providerName: String): Mode =
        if (providerName == "Cloudstream") Mode.ORIGIN_FR else Mode.POPULAR_INTL

    private fun key(providerName: String) = "pref_catalog_filter_$providerName"

    fun get(providerName: String): Mode = try {
        val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
        val saved = prefs.getString(key(providerName), null)
        if (saved == null) defaultFor(providerName) else Mode.fromPref(saved)
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
