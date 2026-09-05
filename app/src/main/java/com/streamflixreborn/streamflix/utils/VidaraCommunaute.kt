package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Partage de la communaute — bibliotheques VIDARA des amis, mises en commun.
 *
 * 2026-09-05 (user : « on avait fait un dossier pour les amis dans le TV Hub, a la base
 *   pour VOE, la on va le reprendre pour Vidara ; on pourra mettre plusieurs comptes sur
 *   le meme dossier ») : copie de VoeCommunaute adaptee a Vidara. Meme principe :
 *   AUCUNE cle dans l'APK. Les cles des amis vivent dans le secret GitHub VIDARA_AMIS
 *   ("prenom:CLE1,autre:CLE2") du depot nx-data ; le workflow refresh_vidara_amis.yml
 *   publie data/vidara_amis.json (titre -> filecode + dossier + libelle du contributeur).
 *   Lecture publique via https://vidara.to/e/{filecode} (VidaraExtractor).
 *
 *   Un groupe par contributeur (champ `lib`) et par dossier : « Prenom - Dossier ».
 *   Ids `livehub::vidaracom::<code>`, distincts de `livehub::vidara::` (bibliotheque perso).
 */
object VidaraCommunaute {

    /**
     * ⚠ INTERRUPTEUR DE LA CARTE « Partage de la communaute ».
     *
     * 2026-08-28 (user : « on va desactiver provisoirement ce nouveau dossier, quand on
     *   aura une nouvelle cle a mettre a l'interieur on le reactivera ») : tant que le
     *   secret VOE_KEYS ne contient que la cle du proprietaire, l'index publie n'est
     *   qu'une COPIE de sa bibliotheque perso — deja accessible par la carte
     *   « Film / serie », qui elle passe par l'API avec sa cle. L'entree ferait donc
     *   doublon pour rien.
     *
     * POUR REACTIVER : passer a `true`. Rien d'autre a toucher — le reste de la chaine
     *   (script refresh_voe.py, workflow refresh_voe.yml, cronjob quotidien, secret
     *   VOE_KEYS, lecture `livehub::vidaracom::`) reste en place et fonctionne.
     */
    private const val ACTIVE = true

    private const val TAG = "VidaraCommunaute"

    /**
     * 2026-08-28 — Regex COMPILEE UNE FOIS. Ne pas la remettre en ligne dans
     *   titreAffiche : avec 2401 fichiers, le tri appelait Pattern.compile des
     *   milliers de fois sur le fil principal. Mesure : deux ANR_LOG et ~20 s de
     *   gel a l'ouverture du dossier, pile sur cette pile d'appels.
     */
    private val RE_PREFIXE_ID = Regex("""^\d{2,8}\s*-\s*""")
    private const val BASE = "https://vidara.to"
    private const val INDEX_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data/vidara_amis.json"

    /** L'index est regenere une fois par jour : inutile de le retelecharger souvent. */
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    data class Fichier(
        val code: String,
        /** Nom brut tel que VOE l'a enregistre, extension comprise. */
        val nomBrut: String,
        /** Libelle du contributeur (« perso », « ami »...), tel qu'ecrit dans VOE_KEYS. */
        val lib: String,
        /** Hauteur de l'image en pixels, 0 si inconnue. */
        val q: Int,
        /** Chemin du dossier VOE d'origine (« Films/Action »), vide si aucun. */
        val dossier: String = "",
        /** Duree en secondes (index : champ `duree`), 0 si inconnue. */
        val dureeSec: Int = 0,
    ) {
        val titre: String get() = nomBrut.substringBeforeLast('.', nomBrut).trim()

        /**
         * Nom LISIBLE. Meme regle que VoeLibrary.titreAffiche : les fichiers sont
         * prefixes de leur identifiant TMDB (« 237584 - Mojave »), utile au
         * rapprochement mais illisible sur une tuile.
         */
        val titreAffiche: String
            get() = VidaraLibrary.sansPrefixe(titre).trim().ifBlank { titre }

        val embed: String get() = "$BASE/e/$code"

        /** Meme fichier vu par VidaraLibrary (fiches TMDB, rattachement). */
        fun enBibliotheque(): VidaraLibrary.Fichier =
            VidaraLibrary.Fichier(code = code, nomBrut = nomBrut, dureeSec = dureeSec,
                                  estClip = false, dossier = dossier)
    }

    @Volatile private var cache: List<Fichier> = emptyList()
    @Volatile private var cacheTs: Long = 0L
    private val verrou = Mutex()

    /** Vrai des qu'on a deja quelque chose a montrer, sans declencher de reseau. */
    val actif: Boolean get() = ACTIVE && cache.isNotEmpty()

    /** Vrai si l'entree du TV Hub doit exister. Voir l'interrupteur ACTIVE. */
    val disponible: Boolean get() = ACTIVE

    fun cacheActuel(): List<Fichier> = cache

    /** Telecharge l'index (ou rend le cache s'il est encore frais). */
    suspend fun tout(forcer: Boolean = false): List<Fichier> = withContext(Dispatchers.IO) {
        verrou.withLock {
            val frais = System.currentTimeMillis() - cacheTs < TTL_MS
            if (!forcer && frais && cache.isNotEmpty()) return@withLock cache

            val corps = try {
                // ⚠ 2026-08-28 — CONTOURNEMENT DU CACHE CDN, ne pas retirer.
                //   Mesure : le workflow a commite un nouvel index a 17:50 (avec les
                //   chemins de dossiers), et raw.githubusercontent servait ENCORE celui
                //   de 17:10 plus de dix minutes apres — cote PC comme cote telephone.
                //   L'app affichait donc « Perso (2401) » a plat alors que le depot
                //   contenait la bonne arborescence. Le parametre change toutes les
                //   heures : assez pour percer le cache, pas assez pour re-telecharger
                //   400 Ko a chaque ouverture.
                val antiCache = System.currentTimeMillis() / 3_600_000L
                val req = okhttp3.Request.Builder()
                    .url("$INDEX_URL?h=$antiCache")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json")
                    .build()
                NetworkClient.default.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        Log.w(TAG, "index inaccessible (http ${r.code})")
                        return@withLock cache
                    }
                    r.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                Log.w(TAG, "index KO : ${e.message}")
                return@withLock cache
            }

            val doc = runCatching { JSONObject(corps) }.getOrNull() ?: run {
                Log.w(TAG, "index illisible")
                return@withLock cache
            }
            val tableau = doc.optJSONArray("items") ?: run {
                Log.w(TAG, "index sans champ items")
                return@withLock cache
            }

            val liste = ArrayList<Fichier>(tableau.length())
            for (i in 0 until tableau.length()) {
                val o = tableau.optJSONObject(i) ?: continue
                val code = o.optString("code").trim()
                if (code.isEmpty()) continue
                liste.add(
                    Fichier(
                        code = code,
                        nomBrut = o.optString("titre").ifBlank { o.optString("nom") },
                        lib = o.optString("lib").ifBlank { "ami" },
                        q = o.optInt("q", 0),
                        dossier = o.optString("dossier").trim(),
                        dureeSec = o.optInt("duree", 0),
                    )
                )
            }

            // On ne remplace JAMAIS un cache rempli par une liste vide : une lecture
            //   ratee ne doit pas faire disparaitre l'entree du TV Hub.
            if (liste.isEmpty() && cache.isNotEmpty()) {
                Log.w(TAG, "index vide — on garde les ${cache.size} entrees precedentes")
                return@withLock cache
            }

            cache = liste
            cacheTs = System.currentTimeMillis()
            Log.i(TAG, "index charge : ${liste.size} fichiers, " +
                "contributeurs = ${liste.map { it.lib }.distinct().joinToString(", ")}, " +
                "dossiers = ${liste.map { it.dossier }.filter { it.isNotBlank() }.distinct().size}")
            liste
        }
    }

    /**
     * 2026-09-05 (user : « que le dossier de mon ami affiche une jaquette TMDB comme le
     *   mien ») : titre officiel + affiche TMDB via les fiches de VidaraLibrary (memes
     *   regles : « 12345 - » = film, « tv12345 - » = serie, cache disque partage).
     */
    fun tuileFilm(f: Fichier): TvShow {
        val b = f.enBibliotheque()
        return TvShow(id = "livehub::vidaracom::${f.code}", title = VidaraLibrary.titrePour(b))
            .copy(poster = VidaraLibrary.posterPour(b), banner = VidaraLibrary.posterPour(b))
            .apply { providerName = "TV Hub" }
    }

    /** Categories affichees dans l'entree « Partage de la communaute » : une par contributeur. */
    suspend fun categories(): List<Category> {
        if (!ACTIVE) return emptyList()
        val fichiers = tout()
        if (fichiers.isEmpty()) return emptyList()
        // Fiches TMDB (affiches) des fichiers qui portent un identifiant, avant de
        //   construire les tuiles — quelques requetes, en cache disque ensuite.
        VidaraLibrary.completerFichesPour(fichiers.map { it.enBibliotheque() })
        return categoriesDe(fichiers)
    }

    /** Version sans reseau, pour l'affichage immediat quand le cache est deja chaud. */
    fun categoriesSiDejaCharge(): List<Category> =
        if (!ACTIVE || cache.isEmpty()) emptyList() else categoriesDe(cache)

    /**
     * 2026-08-28 (user : « tout est en vrac, y a pas les dossiers ») : une Category
     *   par DOSSIER du contributeur, nommee « <contributeur> - <dossier> ». Meme
     *   convention que VoeLibrary (« ONYX - <dossier> ») : displayCategories sait
     *   deja regrouper sur le separateur " - ". Les fichiers sans dossier tombent
     *   dans une categorie portant le seul nom du contributeur.
     */
    private fun categoriesDe(fichiers: List<Fichier>): List<Category> =
        fichiers.groupBy { f ->
            val contrib = f.lib.replaceFirstChar { it.uppercase() }
            if (f.dossier.isBlank()) contrib else contrib + " - " + f.dossier
        }
            .toSortedMap()
            .map { (nom, liste) ->
                Category(
                    name = nom,
                    // Cle de tri calculee UNE fois par fichier (cf. RE_PREFIXE_ID).
                    list = liste.map { it to it.titreAffiche.lowercase() }
                        .sortedBy { it.second }
                        .map { tuileFilm(it.first) }
                        .toMutableList(),
                )
            }

    fun fichierDe(code: String): Fichier? = cache.firstOrNull { it.code == code }

    /**
     * 2026-09-05 (user : « quand on fait une recherche dans l'application, ce dossier-la
     *   doit aussi etre appele ») : les fichiers des amis deviennent des SERVEURS DE
     *   SECOURS dans les fiches, comme la bibliotheque perso. Meme rattachement que
     *   VidaraLibrary (identifiant TMDB en tete du nom pour un film, SxxExx pour une
     *   serie, sinon titre + duree), applique a l'index public des amis. Etiquette
     *   NEUTRE « Partage · Vidara » (le contributeur n'est pas affiche).
     */
    suspend fun serveursPour(
        tmdbId: String?,
        titresConnus: Collection<String>,
        annee: Int?,
        estUnFilm: Boolean,
        titrePrincipal: String? = null,
        dureeMinSec: Int? = null,
        dureeMaxSec: Int? = null,
        saison: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> {
        if (!ACTIVE) return emptyList()
        val fichiers = try { tout() } catch (e: Exception) {
            Log.w(TAG, "index amis KO : ${e.message}"); return emptyList()
        }
        if (fichiers.isEmpty()) return emptyList()
        val convertis = fichiers.map {
            VidaraLibrary.Fichier(code = it.code, nomBrut = it.nomBrut, dureeSec = it.dureeSec,
                                  estClip = false, dossier = it.dossier)
        }
        return VidaraLibrary.rattacher(convertis, tmdbId, titresConnus, annee, estUnFilm,
                                       titrePrincipal, dureeMinSec, dureeMaxSec, saison, episode) { f ->
            // (user 2026-09-05 : « on n'affiche pas les gens ») -> etiquette neutre « Vidara »,
            //   le contributeur reste dans l'index (champ lib) mais n'est pas montre.
            Video.Server(id = "livehub::vidaracom::${f.code}", name = "Vidara", src = "$BASE/e/${f.code}")
        }
    }

    /** Serveur de lecture — identique a VoeLibrary : l'embed public suffit. */
    fun serveurDe(code: String, nom: String = "Communaute"): Video.Server =
        Video.Server(
            id = "livehub::vidaracom::$code",
            name = nom,
            src = "$BASE/e/$code",
        )
}
