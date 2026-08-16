package com.streamflixreborn.streamflix.utils

import androidx.preference.PreferenceManager
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * 2026-05-26 — Filtre par genre TMDB pour les écrans Films/Séries.
 * Genres stables (IDs TMDB ne changent pas). Les providers TMDB
 * (Cloudstream, Movix, TMDb, NetMirror) supportent getGenre(id, page).
 * Les autres providers (Wiflix, FrenchStream, Papa, IPTV…) sont exclus.
 *
 * Le choix est mémorisé PAR PROVIDER (clé pref_genre_filter_<provider>).
 * null = pas de filtre genre (affiche tout, comportement par défaut).
 */
object GenreFilter {

    data class GenreEntry(val id: String, val name: String)

    /** Liste des genres TMDB en français (IDs stables, identiques films/séries) */
    val genres = listOf(
        GenreEntry("28", "Action"),
        GenreEntry("12", "Aventure"),
        GenreEntry("16", "Animation"),
        GenreEntry("35", "Comédie"),
        GenreEntry("80", "Crime"),
        GenreEntry("99", "Documentaire"),
        GenreEntry("18", "Drame"),
        GenreEntry("10751", "Famille"),
        GenreEntry("14", "Fantaisie"),
        GenreEntry("27", "Horreur"),
        GenreEntry("10402", "Musique"),
        GenreEntry("9648", "Mystère"),
        GenreEntry("10749", "Romance"),
        GenreEntry("878", "Science-Fiction"),
        GenreEntry("53", "Thriller"),
        GenreEntry("10752", "Guerre"),
        GenreEntry("37", "Western"),
    )

    /** 2026-05-26 : genres AnimeSama (id = nom du genre, passé en query genre[]=$id).
     *  Liste extraite directement du catalogue anime-sama.to (107 genres). */
    val animeSamaGenres = listOf(
        GenreEntry("Action", "Action"),
        GenreEntry("Adolescence", "Adolescence"),
        GenreEntry("Aliens / Extra-terrestres", "Aliens / Extra-terrestres"),
        GenreEntry("Amitié", "Amitié"),
        GenreEntry("Amour", "Amour"),
        GenreEntry("Apocalypse", "Apocalypse"),
        GenreEntry("Art", "Art"),
        GenreEntry("Arts martiaux", "Arts martiaux"),
        GenreEntry("Assassinat", "Assassinat"),
        GenreEntry("Autre monde", "Autre monde"),
        GenreEntry("Aventure", "Aventure"),
        GenreEntry("Combats", "Combats"),
        GenreEntry("Comédie", "Comédie"),
        GenreEntry("Crime", "Crime"),
        GenreEntry("Cyberpunk", "Cyberpunk"),
        GenreEntry("Danse", "Danse"),
        GenreEntry("Démons", "Démons"),
        GenreEntry("Détective", "Détective"),
        GenreEntry("Donghua", "Donghua"),
        GenreEntry("Dragon", "Dragon"),
        GenreEntry("Drame", "Drame"),
        GenreEntry("Ecchi", "Ecchi"),
        GenreEntry("Ecole", "Ecole"),
        GenreEntry("Elfe", "Elfe"),
        GenreEntry("Enquête", "Enquête"),
        GenreEntry("Famille", "Famille"),
        GenreEntry("Fantastique", "Fantastique"),
        GenreEntry("Fantasy", "Fantasy"),
        GenreEntry("Fantômes", "Fantômes"),
        GenreEntry("Futur", "Futur"),
        GenreEntry("Gastronomie", "Gastronomie"),
        GenreEntry("Ghibli", "Ghibli"),
        GenreEntry("Guerre", "Guerre"),
        GenreEntry("Harcèlement", "Harcèlement"),
        GenreEntry("Harem", "Harem"),
        GenreEntry("Harem inversé", "Harem inversé"),
        GenreEntry("Histoire", "Histoire"),
        GenreEntry("Historique", "Historique"),
        GenreEntry("Horreur", "Horreur"),
        GenreEntry("Isekai", "Isekai"),
        GenreEntry("Jeunesse", "Jeunesse"),
        GenreEntry("Jeux", "Jeux"),
        GenreEntry("Jeux vidéo", "Jeux vidéo"),
        GenreEntry("Josei", "Josei"),
        GenreEntry("Journalisme", "Journalisme"),
        GenreEntry("Kaï", "Kaï"),
        GenreEntry("Mafia", "Mafia"),
        GenreEntry("Magical girl", "Magical girl"),
        GenreEntry("Magie", "Magie"),
        GenreEntry("Maladie", "Maladie"),
        GenreEntry("Mariage", "Mariage"),
        GenreEntry("Mature", "Mature"),
        GenreEntry("Mechas", "Mechas"),
        GenreEntry("Médiéval", "Médiéval"),
        GenreEntry("Militaire", "Militaire"),
        GenreEntry("Monde virtuel", "Monde virtuel"),
        GenreEntry("Monstres", "Monstres"),
        GenreEntry("Musique", "Musique"),
        GenreEntry("Mystère", "Mystère"),
        GenreEntry("Nekketsu", "Nekketsu"),
        GenreEntry("Ninjas", "Ninjas"),
        GenreEntry("Nostalgie", "Nostalgie"),
        GenreEntry("Paranormal", "Paranormal"),
        GenreEntry("Philosophie", "Philosophie"),
        GenreEntry("Pirates", "Pirates"),
        GenreEntry("Police", "Police"),
        GenreEntry("Politique", "Politique"),
        GenreEntry("Post-apocalyptique", "Post-apocalyptique"),
        GenreEntry("Pouvoirs psychiques", "Pouvoirs psychiques"),
        GenreEntry("Préhistoire", "Préhistoire"),
        GenreEntry("Prison", "Prison"),
        GenreEntry("Psychologique", "Psychologique"),
        GenreEntry("Quotidien", "Quotidien"),
        GenreEntry("Religion", "Religion"),
        GenreEntry("Réincarnation / Transmigration", "Réincarnation / Transmigration"),
        GenreEntry("Romance", "Romance"),
        GenreEntry("Samouraïs", "Samouraïs"),
        GenreEntry("School Life", "School Life"),
        GenreEntry("Science-Fantasy", "Science-Fantasy"),
        GenreEntry("Science-fiction", "Science-fiction"),
        GenreEntry("Scientifique", "Scientifique"),
        GenreEntry("Seinen", "Seinen"),
        GenreEntry("Shôjo", "Shôjo"),
        GenreEntry("Shôjo-Ai", "Shôjo-Ai"),
        GenreEntry("Shônen", "Shônen"),
        GenreEntry("Shônen-Ai", "Shônen-Ai"),
        GenreEntry("Slice of Life", "Slice of Life"),
        GenreEntry("Société", "Société"),
        GenreEntry("Sport", "Sport"),
        GenreEntry("Super pouvoirs", "Super pouvoirs"),
        GenreEntry("Super-héros", "Super-héros"),
        GenreEntry("Surnaturel", "Surnaturel"),
        GenreEntry("Survie", "Survie"),
        GenreEntry("Survival game", "Survival game"),
        GenreEntry("Technologies", "Technologies"),
        GenreEntry("Thriller", "Thriller"),
        GenreEntry("Tournois", "Tournois"),
        GenreEntry("Travail", "Travail"),
        GenreEntry("Vampires", "Vampires"),
        GenreEntry("Vengeance", "Vengeance"),
        GenreEntry("Voyage", "Voyage"),
        GenreEntry("Voyage temporel", "Voyage temporel"),
        GenreEntry("Webcomic", "Webcomic"),
        GenreEntry("Yakuza", "Yakuza"),
        GenreEntry("Yaoi", "Yaoi"),
        GenreEntry("Yokai", "Yokai"),
        GenreEntry("Yuri", "Yuri"),
    )

    /**
     * ── 2026-08-18 — GENRES DES SÉRIES : TMDB N'UTILISE PAS LES MÊMES IDENTIFIANTS ──────
     *
     * Remonté par un utilisateur : « est-ce que tu envisages de corriger les genres dans la
     * section séries dans les providers Films/Séries qui proposent les genres (par exemple
     * Movix) ». Le commentaire d'origine de ce fichier affirmait que les IDs étaient
     * « identiques films/séries » — c'est FAUX, et c'est la cause du bug.
     *
     * Mesuré le 2026-08-18 sur `discover/tv` avec la clé du projet :
     *   genre 28 (Action)        → 0 série        genre 10759 (Action & Aventure) → 10 105
     *   genre 878 (Sci-Fi)       → 0 série        genre 10765 (SF & Fantastique)  →  9 300
     *   genre 53 (Thriller)      → 0 série        genre 10768 (Guerre & Politique)→  2 955
     *   genre 12, 14, 27, 10752  → 0 série        genre 18 (Drame)                → 52 074
     *
     * Autrement dit, 9 des 17 genres proposés renvoyaient une liste vide dans l'onglet
     * Séries. On expose donc une liste DÉDIÉE aux séries, avec les vrais identifiants TV.
     */
    val genresSeries = listOf(
        GenreEntry("10759", "Action & Aventure"),
        GenreEntry("16", "Animation"),
        GenreEntry("35", "Comédie"),
        GenreEntry("80", "Crime"),
        GenreEntry("99", "Documentaire"),
        GenreEntry("18", "Drame"),
        GenreEntry("10751", "Famille"),
        GenreEntry("10762", "Enfants"),
        GenreEntry("9648", "Mystère"),
        GenreEntry("10765", "Science-Fiction & Fantastique"),
        GenreEntry("10768", "Guerre & Politique"),
        GenreEntry("10764", "Téléréalité"),
        GenreEntry("10767", "Talk-show"),
        GenreEntry("10766", "Feuilleton"),
        GenreEntry("10763", "Info"),
        GenreEntry("37", "Western"),
    )

    /** Retourne la liste de genres adaptée au provider actif ET à l'onglet (films ou séries). */
    fun genresForProvider(
        providerName: String? = UserPreferences.currentProvider?.name,
        type: YearFilter.Type = YearFilter.Type.FILMS,
    ): List<GenreEntry> = when {
        // AnimeSama filtre par NOM de genre : la même liste vaut pour les deux onglets.
        providerName == "AnimeSama" -> animeSamaGenres
        type == YearFilter.Type.SERIES -> genresSeries
        else -> genres
    }

    /** Le filtre genre est dispo pour les providers TMDB-based + AnimeSama. */
    fun isSupported(providerName: String?): Boolean =
        providerName == "Cloudstream" ||
        providerName == "Movix" ||
        providerName == "NetMirror" ||
        providerName == "AnimeSama" ||
        providerName?.startsWith("TMDb") == true

    fun isSupported(): Boolean = isSupported(UserPreferences.currentProvider?.name)

    /**
     * 2026-08-18 : le genre est mémorisé SÉPARÉMENT pour les films et pour les séries —
     * les identifiants ne sont plus les mêmes (28 côté film, 10759 côté série), donc une
     * clé unique appliquerait un identifiant de série à l'onglet Films et vice-versa.
     * La clé des FILMS ne change pas : le choix déjà enregistré par l'utilisateur survit.
     */
    private fun key(providerName: String, type: YearFilter.Type) =
        if (type == YearFilter.Type.SERIES) "pref_genre_filter_series_$providerName"
        else "pref_genre_filter_$providerName"

    /** Genre sélectionné pour ce provider et cet onglet, ou null = pas de filtre (tout). */
    fun get(
        providerName: String,
        type: YearFilter.Type = YearFilter.Type.FILMS,
    ): GenreEntry? = try {
        val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
        val savedId = prefs.getString(key(providerName, type), null)
        if (savedId == null) null
        else genresForProvider(providerName, type).find { it.id == savedId }
    } catch (_: Exception) {
        null
    }

    /** Sauvegarde le genre sélectionné. null = tout (efface la pref). */
    fun set(
        providerName: String,
        genre: GenreEntry?,
        type: YearFilter.Type = YearFilter.Type.FILMS,
    ) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            if (genre == null) {
                prefs.edit().remove(key(providerName, type)).apply()
            } else {
                prefs.edit().putString(key(providerName, type), genre.id).apply()
            }
        } catch (_: Exception) {
        }
    }

    /** Genre ID courant pour le provider actif et l'onglet demandé, ou null. */
    fun currentGenreId(type: YearFilter.Type = YearFilter.Type.FILMS): String? {
        val name = UserPreferences.currentProvider?.name ?: return null
        return get(name, type)?.id
    }

    /** Label du genre courant pour le provider actif, ou null. */
    fun currentLabel(type: YearFilter.Type = YearFilter.Type.FILMS): String? {
        val name = UserPreferences.currentProvider?.name ?: return null
        return get(name, type)?.name
    }

    // ── Filtre LANGUE AnimeSama (VF / VOSTFR / tous) ──

    private fun langKey(providerName: String) = "pref_lang_filter_$providerName"

    /** Langue sélectionnée pour AnimeSama : "vf", "vostfr", ou null (= tous). */
    fun getLang(providerName: String): String? = try {
        val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
        prefs.getString(langKey(providerName), null)
    } catch (_: Exception) { null }

    fun setLang(providerName: String, lang: String?) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            if (lang == null) prefs.edit().remove(langKey(providerName)).apply()
            else prefs.edit().putString(langKey(providerName), lang).apply()
        } catch (_: Exception) {}
    }

    /** Langue courante pour le provider actif, ou null. */
    fun currentLang(): String? {
        val name = UserPreferences.currentProvider?.name ?: return null
        return getLang(name)
    }

    /** Label lisible pour la langue courante. */
    fun currentLangLabel(): String? = when (currentLang()) {
        "vf" -> "VF"
        "vostfr" -> "VOSTFR"
        else -> null
    }
}
