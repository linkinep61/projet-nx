package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * 2026-08-04 (demande user) — CATÉGORIES EN TÊTE DES RECHERCHES VOD.
 *
 * « Dans toutes les recherches VOD je voudrais des nouvelles catégories : une catégorie
 * uniquement avec Netflix, Amazon, etc., avant les genres. Et une catégorie Nouveautés où il
 * n'y a que les sorties de l'année en cours. Les plus récents d'abord. »
 *
 * Trois providers listent les genres TMDB à requête vide — Cloudstream, Movix et NetMirror.
 * Cette logique est donc mise en commun ici plutôt que triplicée : les trois exposent les
 * mêmes catégories, et une correction profite aux trois.
 *
 * Ces entrées sont de simples [Genre] : mêmes tuiles, même navigation, aucun écran à créer.
 * Seul l'identifiant les distingue, et [estCategorieSpeciale] permet à chaque provider de
 * l'intercepter avant sa logique de genre habituelle.
 *
 * ⚠ [REGION] est indispensable sur les requêtes par plateforme : les catalogues diffèrent d'un
 * pays à l'autre. Sans lui, TMDB répond sur le catalogue américain — donc des titres que
 * l'utilisateur français ne peut pas voir.
 */
object VodCategories {

    private const val ID_NOUVEAUTES = "onyx-nouveautes"
    private const val PREFIXE_PLATEFORME = "onyx-wp:"
    private const val REGION = "FR"

    /**
     * 2026-08-04 : délai laissé aux hébergeurs pour publier leurs sources après une sortie.
     * Un titre plus récent que ce délai est écarté — sans ça on proposerait des fiches sans
     * aucun serveur, et l'app n'a aucun moyen de détecter ce cas avant de tout interroger.
     * Netflix y échappe (cf. `charger`) : ses sorties sont sourcées le jour même.
     */
    private const val DELAI_SOURCES_JOURS = 7

    /**
     * 2026-08-04 (idée user) — DEUX DÉLAIS SELON LE MODE DE SORTIE.
     *
     * « Pour tout film et série de plateformes, hors cinéma, on les affiche 7 jours après.
     *   Et pour tout ce qui est vraiment sortie cinéma, tu mets 2 mois. »
     *
     * Un délai unique ne pouvait pas convenir aux deux : une sortie plateforme est sourcée en
     * quelques jours, alors qu'un film sorti en salles n'a aucune copie correcte avant
     * l'ouverture de la fenêtre vidéo — environ quatre mois en France (trois pour les films
     * ayant fait moins de cent mille entrées). Deux mois est le compromis retenu : assez tard
     * pour que les premières copies convenables circulent, assez tôt pour rester une nouveauté.
     *
     * TMDB étiquette chaque date de sortie par TYPE et par PAYS, ce qui permet de distinguer
     * les deux : 2 et 3 = salles, 4 = numérique, 6 = télévision.
     *
     * Vérifié en direct sur l'API le 2026-08-04, région FR, année en cours, quinze votes :
     *   types 2|3 bornés à soixante jours → 193 films, le plus récent daté du 3 juin ;
     *   types 4|6 bornés à sept jours     → 1864 films, le plus récent du 25 juillet.
     *
     * ⚠ Ces types ne servent PAS à exclure — les deux requêtes sont RÉUNIES. Un film de cinéma
     *   n'est pas supprimé, il arrive simplement plus tard. C'est la différence avec la
     *   tentative écartée le matin même (`withReleaseType` seul, en filtre unique), qui aurait
     *   effectivement fait perdre tout le catalogue des films encore en salles.
     */
    private const val DELAI_CINEMA_JOURS = 60

    /** Sorties numériques et télévisées : plateformes, direct-to-video, diffusions. */
    private val TYPES_PLATEFORME
        get() = TMDb3.Params.WithBuilder<TMDb3.Movie.ReleaseType>("4|6")

    /** Sorties en salles, complètes ou limitées. */
    private val TYPES_CINEMA
        get() = TMDb3.Params.WithBuilder<TMDb3.Movie.ReleaseType>("2|3")

    /**
     * 2026-08-04 (constat log : la série chinoise `唐山凤羽集` remontait en tête de Nouveautés,
     * refusée par TOUS les providers — `workMatch=false`, `sharesWord=false`, Movix 404).
     *
     * Trier par date sans filtre de notoriété fait surgir tout ce que TMDB recense, y compris
     * des productions confidentielles que personne n'a jamais sourcées — et dont le titre, en
     * caractères non latins, ne peut de toute façon correspondre à aucun catalogue français.
     *
     * Faute de pouvoir détecter l'absence de serveurs avant de tout interroger, le nombre de
     * votes est le meilleur indicateur indirect : un titre qu'un public a vu et noté a des
     * chances d'avoir été sourcé.
     *
     * ⚠ NE PAS REMONTER CE SEUIL. Le nombre de votes s'ACCUMULE AVEC LE TEMPS : un film sorti
     * il y a deux semaines, même largement disponible, n'en a souvent que vingt ou trente.
     * Un seuil élevé écarte donc précisément les nouveautés — l'inverse du but de la catégorie.
     * Essayé à 60 le 2026-08-04 : l'utilisateur a perdu la plupart de ses films récents qui
     * avaient pourtant des serveurs. Ramené à 15, valeur qu'il a validée à l'usage.
     */
    private const val VOTES_MINIMUM = 15

    private val anneeCourante: Int get() = Calendar.getInstance().get(Calendar.YEAR)

    // ════════════════════════════════════════════════════════════════════════
    //  FILMS ACTUELLEMENT EN SALLES — liste d'écartement partagée
    // ════════════════════════════════════════════════════════════════════════
    /**
     * 2026-08-04 — les accueils qui répliquent une liste « populaires » ou « tendances »
     * (Movix, et toute rangée bâtie sur `trending`/`popular`) ne peuvent PAS appliquer la
     * règle des deux mois : TMDB ne renvoie aucun type de sortie sur ces points d'entrée.
     *
     * On prend donc le problème à l'envers : une seule requête donne les films SORTIS EN
     * SALLES en France depuis moins de deux mois, c'est-à-dire exactement ceux dont aucune
     * copie correcte ne circule encore. Il suffit d'écarter ces identifiants des rangées.
     *
     * Trié par popularité : les trois premières pages couvrent tout ce qu'un accueil peut
     * mettre en avant. Relevé du 2026-08-04 — en tête : Spider-Man Brand New Day, L'Odyssée,
     * Supergirl, Toy Story 5, Vaiana. Ce sont précisément les fiches sans serveur signalées.
     */
    private const val PAGES_SALLES = 3
    private const val CACHE_SALLES_MS = 6L * 60 * 60 * 1000

    private val verrouSalles = Mutex()
    private var salles: Set<String> = emptySet()
    private var sallesExpire = 0L

    /** Ramasse les identifiants d'une requête `discover/movie` sur plusieurs pages. */
    private suspend fun idsDeDiscover(
        pages: Int,
        depuis: Calendar,
        types: TMDb3.Params.WithBuilder<TMDb3.Movie.ReleaseType>?,
    ): Set<String> {
        val trouves = mutableSetOf<String>()
        for (p in 1..pages) {
            val reponse = runCatching {
                TMDb3.Discover.movie(
                    language = "fr-FR", page = p, region = REGION,
                    releaseDate = TMDb3.Params.Range(gte = depuis, lte = Calendar.getInstance()),
                    withReleaseType = types,
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                )
            }.getOrNull() ?: break
            reponse.results.forEach { trouves.add(it.id.toString()) }
            if (p >= reponse.totalPages) break
        }
        return trouves
    }

    /** Identifiants TMDB des films encore en salles en France. Vide en cas d'échec réseau. */
    suspend fun enSalles(): Set<String> = verrouSalles.withLock {
        val maintenant = System.currentTimeMillis()
        if (salles.isNotEmpty() && maintenant < sallesExpire) return@withLock salles
        val trouves = idsDeDiscover(
            pages = PAGES_SALLES,
            depuis = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -DELAI_CINEMA_JOURS) },
            types = TYPES_CINEMA,
        )
        // Échec réseau → on garde l'ancienne liste plutôt que de ne plus rien écarter.
        if (trouves.isNotEmpty()) {
            salles = trouves
            sallesExpire = maintenant + CACHE_SALLES_MS
        }
        salles
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FILMS SANS AUCUNE SORTIE FRANÇAISE — le cas « The Odyssey » de M. Walz
    // ════════════════════════════════════════════════════════════════════════
    /**
     * 2026-08-04 (user : « dans films populaires, Odyssée est encore là deux mois après »).
     *
     * Vérification faite : il ne s'agit PAS du même film. L'Odyssée de Nolan (1368337, sortie
     * française du 15 juillet) est bien écartée par [enSalles]. Celui qui restait est
     * `The Odyssey` de Marcel Walz (1698863) — une production opportuniste au titre identique,
     * que TMDB place dans ses films populaires et que l'utilisateur prenait pour l'autre.
     *
     * Il échappait à [enSalles] pour une raison simple : il n'a **aucune date de sortie
     * française**, sa seule date étant une sortie numérique américaine. Il n'apparaît donc dans
     * aucune requête région FR.
     *
     * C'est précisément ce qui en fait un bon signal : un film récent que personne n'a distribué
     * en France n'aura jamais de source française. La règle est donc — un film sorti depuis
     * moins de [FENETRE_SORTIE_FR_JOURS] jours doit figurer parmi les sorties françaises,
     * sinon on l'écarte. Au-delà de cette fenêtre on ne filtre plus : les catalogues anciens
     * sont mal renseignés côté dates et on perdrait des titres légitimes.
     */
    private const val FENETRE_SORTIE_FR_JOURS = 120
    private const val PAGES_SORTIES_FR = 8

    private val verrouSortiesFr = Mutex()
    private var sortiesFr: Set<String> = emptySet()
    private var sortiesFrExpire = 0L

    /** Identifiants des films ayant une sortie française récente, tous types confondus. */
    suspend fun sortiesFrancaises(): Set<String> = verrouSortiesFr.withLock {
        val maintenant = System.currentTimeMillis()
        if (sortiesFr.isNotEmpty() && maintenant < sortiesFrExpire) return@withLock sortiesFr
        val trouves = idsDeDiscover(
            pages = PAGES_SORTIES_FR,
            depuis = Calendar.getInstance()
                .apply { add(Calendar.DAY_OF_YEAR, -FENETRE_SORTIE_FR_JOURS) },
            types = null,
        )
        if (trouves.isNotEmpty()) {
            sortiesFr = trouves
            sortiesFrExpire = maintenant + CACHE_SALLES_MS
        }
        sortiesFr
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SATURATION PAR FRANCHISE
    // ════════════════════════════════════════════════════════════════════════
    /**
     * 2026-08-04 (capture user : la rangée « Sur Max » alignait SIX Spider-Man de suite,
     * de 2002 à 2021, avant le moindre autre film).
     *
     * TMDB ne renvoie pas l'appartenance à une collection dans les résultats de `discover`
     * — il faudrait une requête de détail par film. On s'appuie donc sur le titre, qui suffit
     * amplement : les suites reprennent le titre du premier opus et le complètent.
     * « Spider-Man », « Spider-Man : Homecoming », « The Amazing Spider-Man » partagent tous
     * la racine `spiderman` une fois la ponctuation et les accents retirés.
     *
     * La règle est donc — un titre normalisé qui CONTIENT la racine d'un titre déjà retenu
     * (ou qui est contenu par elle) appartient à la même famille, et on n'en garde que
     * [maxParFranchise]. Les autres sont écartés pour laisser la place à autre chose.
     *
     * ⚠ Le test de longueur minimale n'est pas cosmétique : sans lui, un titre court comme
     *   « Up » ou « Her » se retrouverait contenu dans des dizaines de titres sans rapport.
     */
    private const val RACINE_LONGUEUR_MINIMALE = 6

    private fun normaliserTitre(titre: String): String =
        java.text.Normalizer.normalize(titre.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("[^a-z0-9]"), "")

    fun <T> limiterFranchises(
        items: List<T>,
        maxParFranchise: Int = 2,
        titre: (T) -> String?,
    ): List<T> {
        val racines = mutableListOf<Pair<String, Int>>()
        val sortie = mutableListOf<T>()
        for (item in items) {
            val n = normaliserTitre(titre(item).orEmpty())
            if (n.length < RACINE_LONGUEUR_MINIMALE) {
                sortie.add(item)
                continue
            }
            val i = racines.indexOfFirst { (racine, _) ->
                (n.contains(racine) || racine.contains(n)) &&
                    minOf(racine.length, n.length) >= RACINE_LONGUEUR_MINIMALE
            }
            if (i < 0) {
                racines.add(n to 1)
                sortie.add(item)
            } else {
                val (racine, compte) = racines[i]
                if (compte < maxParFranchise) {
                    racines[i] = racine to (compte + 1)
                    sortie.add(item)
                }
            }
        }
        return sortie
    }

    /** Vrai si cette date de sortie tombe dans la fenêtre où l'on exige une sortie française. */
    fun dansLaFenetreFrancaise(dateIso: String?): Boolean {
        if (dateIso.isNullOrBlank()) return false
        val limite = Calendar.getInstance()
            .apply { add(Calendar.DAY_OF_YEAR, -FENETRE_SORTIE_FR_JOURS) }.time
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .parse(dateIso)?.after(limite) ?: false
        }.getOrDefault(false)
    }

    /** Plateformes proposées, dans l'ordre d'affichage — les plus utilisées en France d'abord. */
    private val PLATEFORMES: List<Pair<TMDb3.Provider.WatchProviderId, String>> = listOf(
        TMDb3.Provider.WatchProviderId.NETFLIX to "Netflix",
        TMDb3.Provider.WatchProviderId.AMAZON_PRIME_VIDEO_TIER_B to "Prime Video",
        TMDb3.Provider.WatchProviderId.DISNEY_PLUS to "Disney+",
        TMDb3.Provider.WatchProviderId.APPLE_TV_PLUS to "Apple TV+",
        TMDb3.Provider.WatchProviderId.CANAL_PLUS to "Canal+",
        // ⚠ 2026-08-04 : ces trois-là pointaient sur des identifiants VIDES en France
        //   (Max 384, OCS 56, France TV 2278 → zéro résultat, tuiles mortes depuis le début).
        //   Corrigés d'après /watch/providers/movie?watch_region=FR. Ne pas revenir aux anciens.
        TMDb3.Provider.WatchProviderId.MAX_FR to "Max",
        TMDb3.Provider.WatchProviderId.PARAMOUNT to "Paramount+",
        TMDb3.Provider.WatchProviderId.CINE_OCS_FR to "Ciné+ OCS",
        TMDb3.Provider.WatchProviderId.CRUNCHYROLL to "Crunchyroll",
        TMDb3.Provider.WatchProviderId.ARTE to "Arte",
        TMDb3.Provider.WatchProviderId.FRANCE_TV_FR to "France TV",
    )

    /** Les entrées à placer AVANT les genres classiques. */
    fun enTete(): List<Genre> = listOf(
        Genre(id = ID_NOUVEAUTES, name = "Nouveautés $anneeCourante"),
    ) + PLATEFORMES.map { (wp, libelle) -> Genre(id = PREFIXE_PLATEFORME + wp.id, name = libelle) }

    /** Vrai si cet identifiant est une de nos catégories (et non un genre TMDB). */
    fun estCategorieSpeciale(id: String): Boolean =
        id == ID_NOUVEAUTES || id.startsWith(PREFIXE_PLATEFORME)

    private fun libelleDe(id: String): String =
        if (id == ID_NOUVEAUTES) "Nouveautés $anneeCourante"
        else PLATEFORMES.firstOrNull { PREFIXE_PLATEFORME + it.first.id == id }?.second ?: "Catégorie"

    /**
     * Charge le contenu d'une catégorie. Tri par DATE DÉCROISSANTE dans tous les cas :
     * l'utilisateur veut « les plus récents d'abord », y compris sur les plateformes.
     *
     * @param langue code langue TMDB du provider appelant (ex. « fr-FR »).
     */
    suspend fun charger(id: String, page: Int, langue: String?): Genre {
        val libelle = libelleDe(id)
        val estNouveautes = id == ID_NOUVEAUTES
        val fournisseur: TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>? =
            if (estNouveautes) null else {
                val brut = id.removePrefix(PREFIXE_PLATEFORME).toIntOrNull()
                TMDb3.Provider.WatchProviderId.entries.find { it.id == brut }
                    ?.let { TMDb3.Params.WithBuilder(it) }
            }

        // 2026-08-04 (mise en garde user : « ne pas mettre les films pas encore sortis, on n'aura
        //   pas de serveurs, et on n'a aucune méthode pour détecter un titre sans serveur ») :
        //   trier par date DÉCROISSANTE fait justement remonter EN PREMIER les sorties à venir —
        //   décembre avant janvier. On borne donc la date de sortie à AUJOURD'HUI.
        //   `primaryReleaseDate.lte` remplace `primaryReleaseYear` : il exprime à la fois
        //   « année en cours » (via le gte au 1er janvier) et « déjà sorti » (via le lte).
        // 2026-08-04 (user : « recule les dates d'au moins une semaine après leur sortie, sauf
        //   pour Netflix ») : une borne à aujourd'hui ne suffisait pas. Un film sorti hier passe
        //   le filtre alors qu'aucune source n'existe encore — il faut laisser aux hébergeurs le
        //   temps de le publier.
        //   EXCEPTION Netflix : ses productions sont disponibles dès le jour de sortie, les
        //   sources suivent immédiatement. Lui imposer une semaine de retard masquerait
        //   justement ce qui vient de sortir, c'est-à-dire l'intérêt de la catégorie.
        // 2026-08-04 (user : « les sorties Canal, Amazon, Paramount, Disney, tout ça c'est des
        //   sorties instantanées sur leur plateforme ») — AUCUN délai sur les catégories de
        //   plateforme, quelle qu'elle soit. Figurer au catalogue d'un service, c'est y être
        //   disponible le jour même ; les sources suivent dans la foulée. L'exception était
        //   réservée à Netflix, c'était trop restrictif : elle vaut pour toutes.
        //   Le délai ne concerne donc plus que « Nouveautés », la seule catégorie où un titre
        //   peut n'être encore qu'en salles.
        val delaiPlateforme = if (fournisseur != null) 0 else DELAI_SOURCES_JOURS

        val debutAnnee = Calendar.getInstance().apply {
            set(Calendar.YEAR, anneeCourante); set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val borneBasse = if (estNouveautes) debutAnnee else null

        suspend fun filmsDe(
            types: TMDb3.Params.WithBuilder<TMDb3.Movie.ReleaseType>,
            delaiJours: Int,
        ): List<Movie> = TMDb3.Discover.movie(
            language = langue, page = page,
            // `region` + `releaseDate` — et NON `primaryReleaseDate` : c'est cette combinaison
            //   qui fait porter la borne de date sur la sortie FRANÇAISE du type demandé.
            //   Effet de bord souhaitable : un film sans aucune date française est écarté,
            //   or c'est précisément le profil des productions jamais sourcées en France.
            region = REGION,
            releaseDate = TMDb3.Params.Range(
                gte = borneBasse,
                lte = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -delaiJours) },
            ),
            withReleaseType = types,
            voteCount = TMDb3.Params.Range(gte = VOTES_MINIMUM),
            watchRegion = if (fournisseur != null) REGION else null,
            withWatchProviders = fournisseur,
            // ⚠ TRI PAR DATE, comme demandé — « les plus récents d'abord ».
            //   Un passage en POPULARITY_DESC a été essayé le 2026-08-04 : il remontait des
            //   titres plus anciens et faisait disparaître les nouveautés que l'utilisateur
            //   venait de valider. Combiné à la fenêtre de dates et au seuil de votes,
            //   le tri par date donne le bon résultat. Ne pas y revenir sans raison mesurée.
            sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
        ).results.map { m ->
            Movie(
                id = m.id.toString(), title = m.title, overview = m.overview,
                released = m.releaseDate, rating = m.voteAverage.toDouble(),
                poster = m.posterPath?.w500, banner = m.backdropPath?.w1280,
            )
        }

        return runCatching {
            // RÉUNION des deux régimes, jamais une exclusion : la sortie plateforme au bout
            //   d'une semaine, la sortie en salles au bout de deux mois. Un film qui relève
            //   des deux (salles puis numérique) n'apparaît qu'une fois, dédoublonné sur l'id.
            //   Les catégories de plateforme n'interrogent que le versant numérique : figurer
            //   au catalogue d'un service EST une disponibilité numérique, la seconde requête
            //   n'y apporterait rien. Mesuré sur Netflix : 271 titres avec le filtre de type
            //   contre 282 sans — la perte est négligeable.
            val films = (
                filmsDe(TYPES_PLATEFORME, delaiPlateforme) +
                    if (fournisseur == null) filmsDe(TYPES_CINEMA, DELAI_CINEMA_JOURS)
                    else emptyList()
                )
                .distinctBy { it.id }
                .sortedByDescending { it.released }
            // Côté séries, PAS de distinction cinéma/plateforme : une série n'est jamais
            //   distribuée en salles, elle relève toujours du régime « plateforme ».
            //   TMDB n'expose d'ailleurs pas de type de sortie sur `discover/tv`.
            //   La borne reste donc la semaine — une série annoncée mais non diffusée n'a
            //   aucun épisode, donc aucun serveur.
            val series = TMDb3.Discover.tv(
                language = langue, page = page,
                firstAirDate = TMDb3.Params.Range(
                    gte = borneBasse,
                    lte = Calendar.getInstance()
                        .apply { add(Calendar.DAY_OF_YEAR, -delaiPlateforme) },
                ),
                voteCount = TMDb3.Params.Range(gte = VOTES_MINIMUM),
                watchRegion = if (fournisseur != null) REGION else null,
                withWatchProviders = fournisseur,
                sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,   // cf. commentaire côté films
            ).results.map { t ->
                TvShow(
                    id = t.id.toString(), title = t.name, overview = t.overview,
                    released = t.firstAirDate, rating = t.voteAverage.toDouble(),
                    poster = t.posterPath?.w500, banner = t.backdropPath?.w1280,
                )
            }
            // Alternance films/séries : sinon la grille s'ouvre sur vingt films d'affilée.
            val melange = mutableListOf<Show>()
            for (i in 0 until maxOf(films.size, series.size)) {
                films.getOrNull(i)?.let { melange.add(it) }
                series.getOrNull(i)?.let { melange.add(it) }
            }
            Genre(id = id, name = libelle, shows = melange)
        }.getOrElse { Genre(id = id, name = libelle, shows = emptyList()) }
    }
}
