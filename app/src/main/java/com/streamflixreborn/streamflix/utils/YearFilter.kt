package com.streamflixreborn.streamflix.utils

import androidx.preference.PreferenceManager
import com.streamflixreborn.streamflix.StreamFlixApp
import java.util.Calendar

/**
 * 2026-08-04 (demande user : « dans les providers VOD, serait-il réalisable de trier les films
 * par une date choisie ? On fait un second clic sur Films ou Séries et on pourrait choisir
 * l'année »).
 *
 * Filtre d'ANNÉE du catalogue, mémorisé PAR PROVIDER **et par type** (films / séries) : on
 * peut vouloir les films de 1995 tout en gardant les séries récentes.
 *
 * ⚠ RÉSERVÉ AUX PROVIDERS ADOSSÉS À TMDB. `primary_release_date` et `first_air_date` sont des
 *   paramètres natifs de TMDB Discover, donc gratuits pour Movix, Cloudstream, NetMirror et
 *   TMDb. Les providers SCRAPÉS (Wiflix, FrenchStream, Papadustream, VoirDrama…) lisent des
 *   pages HTML dont la recherche n'expose aucun filtre d'année : il faudrait aspirer tout leur
 *   catalogue pour trier localement. Ne pas tenter de leur greffer ce filtre.
 *
 * Le choix est une PLAGE : une année précise est simplement une plage d'un an. Ça permet
 * d'offrir les décennies sans code séparé.
 */
object YearFilter {

    /** Aucune borne = tout le catalogue. */
    const val AUCUNE = 0

    /**
     * Plage retenue. [debut] et [fin] inclus. `debut == AUCUNE` signifie « toutes les années ».
     */
    data class Plage(val debut: Int, val fin: Int) {
        val actif: Boolean get() = debut != AUCUNE

        /** Libellé court pour l'en-tête et le bouton. */
        val libelle: String
            get() = when {
                !actif -> "Toutes les années"
                debut == fin -> debut.toString()
                else -> "$debut – $fin"
            }

        fun serialise(): String = if (!actif) "" else "$debut-$fin"

        companion object {
            val TOUTES = Plage(AUCUNE, AUCUNE)

            fun deserialise(s: String?): Plage {
                if (s.isNullOrBlank()) return TOUTES
                val p = s.split('-')
                val a = p.getOrNull(0)?.toIntOrNull() ?: return TOUTES
                val b = p.getOrNull(1)?.toIntOrNull() ?: a
                return Plage(a, b)
            }
        }
    }

    /** Films ou séries — deux réglages indépendants. */
    enum class Type(val cle: String) { FILMS("films"), SERIES("series") }

    private val anneeCourante: Int get() = Calendar.getInstance().get(Calendar.YEAR)

    /**
     * Providers où le filtre est RÉELLEMENT branché.
     *
     * ⚠ N'ajouter un nom ici QU'APRÈS avoir câblé son `getMovies`/`getTvShows` : le bouton
     *   ne doit jamais apparaître là où le réglage n'aurait aucun effet.
     *
     * Relevé du 2026-08-04, providers passés en revue un par un :
     *   · TMDB natifs (filtre serveur, une requête, n'importe quelle année) — Movix,
     *     Cloudstream, NetMirror, TMDb. ✅ câblés.
     *   · Année présente dans la liste mais catalogue non paginable par année (Nakios,
     *     Moviebox, aplouf, Franime, DessinAnime) — faisable, non fait : le filtrage serait
     *     purement client et viderait les pages.
     *   · Année ABSENTE de la liste (FrenchStream, FrenchAnime, AnimeSama, VoirAnime,
     *     VoirDrama, FrenchManga, UnJourUnFilm, Frembed, WebJs) — il faudrait ouvrir chaque
     *     fiche, soit vingt requêtes par écran. À ne pas tenter.
     *
     * ⚠⚠ WIFLIX — ESSAYÉ, MESURÉ, ABANDONNÉ. NE PAS REFAIRE.
     *   Son identifiant est le slug, et l'exemple documenté ailleurs dans le projet
     *   (`36373-supergirl-2026.html`) laissait croire que l'année y figurait toujours.
     *   Relevé en direct sur sa page de liste des films : sur 20 fiches, **4 seulement**
     *   portent une année, soit 20 %. Et cette année ne sert qu'à désambiguïser un titre,
     *   elle n'est pas systématique.
     *   Les deux issues étaient donc mauvaises : conserver les fiches sans année rend le
     *   filtre INVISIBLE (constaté par le user — « ça ne marche pas sur Wiflix »), les
     *   écarter masquerait 80 % du catalogue. Retiré sur sa décision.
     */
    fun estSupporte(providerName: String?): Boolean =
        providerName == "Movix" || providerName == "Cloudstream" ||
            providerName == "NetMirror" || providerName?.startsWith("TMDb") == true

    private fun cle(providerName: String, type: Type) =
        "pref_year_filter_${type.cle}_$providerName"

    fun get(providerName: String, type: Type): Plage = try {
        Plage.deserialise(
            PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
                .getString(cle(providerName, type), null)
        )
    } catch (_: Exception) {
        Plage.TOUTES
    }

    fun set(providerName: String, type: Type, plage: Plage) {
        try {
            PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
                .edit().putString(cle(providerName, type), plage.serialise()).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * Choix proposés, dans l'ordre d'affichage : « Toutes les années », puis les quinze
     * dernières années une par une, puis les décennies jusqu'aux années cinquante.
     *
     * Décision user : « année précise + décennies ». Les tranches évitent de faire défiler
     * soixante-dix lignes à la télécommande pour atteindre un vieux film.
     */
    fun choix(): List<Plage> {
        val liste = mutableListOf(Plage.TOUTES)
        val courante = anneeCourante
        for (a in courante downTo courante - 14) liste.add(Plage(a, a))
        var decennie = ((courante - 15) / 10) * 10
        while (decennie >= 1950) {
            liste.add(Plage(decennie, decennie + 9))
            decennie -= 10
        }
        return liste
    }

    // ── Conversion vers les bornes TMDB ────────────────────────────────────────
    /** 1ᵉʳ janvier de [Plage.debut], ou `null` si le filtre est inactif. */
    fun borneBasse(plage: Plage): Calendar? {
        if (!plage.actif) return null
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, plage.debut)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }

    /** 31 décembre de [Plage.fin], ou `null` si le filtre est inactif. */
    fun borneHaute(plage: Plage): Calendar? {
        if (!plage.actif) return null
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, plage.fin)
            set(Calendar.MONTH, Calendar.DECEMBER)
            set(Calendar.DAY_OF_MONTH, 31)
        }
    }

    /** Format `yyyy-MM-dd` pour les providers qui parlent à TMDB en chaînes (Movix). */
    fun texte(cal: Calendar?): String? = cal?.let {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(it.time)
    }
}
