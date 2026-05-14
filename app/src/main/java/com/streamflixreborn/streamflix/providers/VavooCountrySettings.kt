package com.streamflixreborn.streamflix.providers

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * 2026-05-13 (user "tu peux pas faire comme MyIPTV et choisir la langue") :
 * picker pays pour Vavoo. La liste FR (default) reste exactement comme avant
 * (logos curated + tri TNT). Si user change pour un autre pays, on envoie
 * `filter.group=<NomPays>` à l'API Vavoo et on accepte tous les channels
 * retournés (sans filtre client-side spécifique au pays).
 *
 * Phase 1 : liste hardcodée des pays courants, France en tête.
 * Phase 2 (TODO) : option "Monde" qui itère tous les pays et combine.
 */
object VavooCountrySettings {

    private const val PREF_KEY = "vavoo_country_filter"

    /** Code interne (utilisé en clé pref). filterValue = string envoyé à l'API. */
    data class Country(val code: String, val flag: String, val label: String, val filterValue: String)

    /** France EN PREMIER. C'est le mode par défaut (back-compat exact). Tous les
     *  autres pays passent `filter.group=<NomEnAnglais>` à Vavoo — certains
     *  peuvent retourner 0 résultats si le label exact diffère, à affiner par
     *  retour user. */
    /** Sentinel filterValue pour "Monde entier" — déclenche le mode multi-pays
     *  côté VavooProvider (itère tous les pays connus + combine). */
    const val ALL_COUNTRIES = "__ALL__"

    val list = listOf(
        Country("fr", "🇫🇷", "France", "France"),
        Country("all", "🌍", "Monde entier", ALL_COUNTRIES),
        Country("it", "🇮🇹", "Italie", "Italy"),
        Country("de", "🇩🇪", "Allemagne", "Germany"),
        Country("uk", "🇬🇧", "Royaume-Uni", "United Kingdom"),
        Country("es", "🇪🇸", "Espagne", "Spain"),
        Country("pt", "🇵🇹", "Portugal", "Portugal"),
        Country("be", "🇧🇪", "Belgique", "Belgium"),
        Country("nl", "🇳🇱", "Pays-Bas", "Netherlands"),
        Country("ch", "🇨🇭", "Suisse", "Switzerland"),
        Country("at", "🇦🇹", "Autriche", "Austria"),
        Country("us", "🇺🇸", "USA", "United States"),
        Country("ca", "🇨🇦", "Canada", "Canada"),
        Country("tr", "🇹🇷", "Turquie", "Turkey"),
        Country("gr", "🇬🇷", "Grèce", "Greece"),
        Country("pl", "🇵🇱", "Pologne", "Poland"),
        Country("ar", "🇸🇦", "Pays arabes", "Arabic"),
    )

    /** Liste des pays à itérer pour "Monde entier" (tous sauf ALL_COUNTRIES). */
    val allCountryFilterValues: List<String> = list
        .filter { it.filterValue != ALL_COUNTRIES }
        .map { it.filterValue }

    fun getCurrent(context: Context): Country {
        val code = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, "fr") ?: "fr"
        return list.find { it.code == code } ?: list.first()
    }

    fun setCurrent(context: Context, country: Country) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY, country.code)
            .apply()
    }
}
