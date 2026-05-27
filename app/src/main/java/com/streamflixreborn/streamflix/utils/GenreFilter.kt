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

    /** Retourne la liste de genres adaptée au provider actif. */
    fun genresForProvider(providerName: String? = UserPreferences.currentProvider?.name): List<GenreEntry> =
        if (providerName == "AnimeSama") animeSamaGenres else genres

    /** Le filtre genre est dispo pour les providers TMDB-based + AnimeSama. */
    fun isSupported(providerName: String?): Boolean =
        providerName == "Cloudstream" ||
        providerName == "Movix" ||
        providerName == "NetMirror" ||
        providerName == "AnimeSama" ||
        providerName?.startsWith("TMDb") == true

    fun isSupported(): Boolean = isSupported(UserPreferences.currentProvider?.name)

    private fun key(providerName: String) = "pref_genre_filter_$providerName"

    /** Genre sélectionné pour ce provider, ou null = pas de filtre (tout). */
    fun get(providerName: String): GenreEntry? = try {
        val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
        val savedId = prefs.getString(key(providerName), null)
        if (savedId == null) null else genresForProvider(providerName).find { it.id == savedId }
            ?: genres.find { it.id == savedId }
    } catch (_: Exception) {
        null
    }

    /** Sauvegarde le genre sélectionné. null = tout (efface la pref). */
    fun set(providerName: String, genre: GenreEntry?) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            if (genre == null) {
                prefs.edit().remove(key(providerName)).apply()
            } else {
                prefs.edit().putString(key(providerName), genre.id).apply()
            }
        } catch (_: Exception) {
        }
    }

    /** Genre ID courant pour le provider actif, ou null. */
    fun currentGenreId(): String? {
        val name = UserPreferences.currentProvider?.name ?: return null
        return get(name)?.id
    }

    /** Label du genre courant pour le provider actif, ou null. */
    fun currentLabel(): String? {
        val name = UserPreferences.currentProvider?.name ?: return null
        return get(name)?.name
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
