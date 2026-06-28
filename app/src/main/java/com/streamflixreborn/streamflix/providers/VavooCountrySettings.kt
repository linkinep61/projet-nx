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

    // 2026-06-26 : liste ALIGNÉE sur les vrais `group` de Vavoo officiel
    //   (récupérés via features.filter du catalogue mediahubmx-catalog.json).
    //   Vrais groups : Albania, Arabia, Balkans, Bulgaria, Croatia, France,
    //   France Sport, Germany, Italy, Netherlands, Poland, Portugal, Romania,
    //   Russia, Spain, Turkey, United Kingdom.
    //   RETIRÉS (n'existent PAS sur Vavoo → renvoyaient 0 chaîne) : Belgique,
    //   Suisse, Autriche, USA, Grèce. "Arabic" corrigé en "Arabia".
    //   2026-06-27 (user) : Russia RÉACTIVÉE (vrai groupe Vavoo). + Canada =
    //   filterValue spécial CANADA_HEURISTIC (pas un vrai groupe Vavoo : on
    //   regroupe par heuristique de noms côté VavooProvider).
    const val CANADA_HEURISTIC = "__CANADA__"
    val list = listOf(
        Country("fr", "🇫🇷", "France", "France"),
        Country("all", "🌍", "Monde entier", ALL_COUNTRIES),
        Country("frsport", "⚽", "France Sport", "France Sport"),
        Country("ca", "🇨🇦", "Canada", CANADA_HEURISTIC),
        Country("de", "🇩🇪", "Allemagne", "Germany"),
        Country("it", "🇮🇹", "Italie", "Italy"),
        Country("uk", "🇬🇧", "Royaume-Uni", "United Kingdom"),
        Country("es", "🇪🇸", "Espagne", "Spain"),
        Country("pt", "🇵🇹", "Portugal", "Portugal"),
        Country("nl", "🇳🇱", "Pays-Bas", "Netherlands"),
        Country("pl", "🇵🇱", "Pologne", "Poland"),
        Country("ro", "🇷🇴", "Roumanie", "Romania"),
        Country("bg", "🇧🇬", "Bulgarie", "Bulgaria"),
        Country("hr", "🇭🇷", "Croatie", "Croatia"),
        Country("al", "🇦🇱", "Albanie", "Albania"),
        Country("balkans", "🌍", "Balkans", "Balkans"),
        Country("tr", "🇹🇷", "Turquie", "Turkey"),
        Country("ru", "🇷🇺", "Russie", "Russia"),
        Country("ar", "🇸🇦", "Pays arabes", "Arabia"),
    )

    /** Liste des pays à itérer pour "Monde entier" (tous sauf ALL_COUNTRIES). */
    val allCountryFilterValues: List<String> = list
        .filter { it.filterValue != ALL_COUNTRIES && it.filterValue != CANADA_HEURISTIC }
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
