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
 * Partage de la communaute — bibliotheques VOE mises en commun.
 *
 * 2026-08-28 (decision user) : meme rendu que « Film / serie », mais SANS aucune
 *   cle API dans l'APK. La cle d'un contributeur vit dans le secret GitHub
 *   `VOE_KEYS` du depot nx-data ; un workflow quotidien (refresh_voe.yml)
 *   interroge l'API VOE et publie la seule table titre -> filecode dans
 *   data/voe.json. L'app ne lit QUE ce fichier public.
 *
 *   Pourquoi ce detour : la cle VOE autorise /api/file/delete sans autre
 *   garde-fou (verifie sur leur documentation), et un APK se decompresse. La
 *   mettre en dur reviendrait a donner le compte du contributeur a tout le
 *   monde. Cote lecture il n'y a rien a proteger : `voe.sx/e/{filecode}` est un
 *   lien public qui ne demande aucune authentification — mesure faite sur trois
 *   fichiers tires au hasard, tous lus sans cle.
 *
 * Difference avec [VoeLibrary], et il ne faut pas les confondre :
 *   - VoeLibrary  = TA bibliotheque, interrogee EN DIRECT avec BuildConfig.VOE_API_KEY,
 *                   arborescence de dossiers, carte « Film / serie ».
 *   - VoeCommunaute = l'index PUBLIC, a plat, une entree par contributeur (champ
 *                   `lib`), entree « Partage de la communaute » dans Autres Replays.
 *   Les deux peuvent contenir le meme fichier sans se genrer : ids distincts
 *   (`livehub::voe::` contre `livehub::voecom::`).
 */
object VoeCommunaute {

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
     *   VOE_KEYS, lecture `livehub::voecom::`) reste en place et fonctionne.
     */
    private const val ACTIVE = false

    private const val TAG = "VoeCommunaute"

    /**
     * 2026-08-28 — Regex COMPILEE UNE FOIS. Ne pas la remettre en ligne dans
     *   titreAffiche : avec 2401 fichiers, le tri appelait Pattern.compile des
     *   milliers de fois sur le fil principal. Mesure : deux ANR_LOG et ~20 s de
     *   gel a l'ouverture du dossier, pile sur cette pile d'appels.
     */
    private val RE_PREFIXE_ID = Regex("""^\d{2,8}\s*-\s*""")
    private const val BASE = "https://voe.sx"
    private const val INDEX_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data/voe.json"

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
    ) {
        val titre: String get() = nomBrut.substringBeforeLast('.', nomBrut).trim()

        /**
         * Nom LISIBLE. Meme regle que VoeLibrary.titreAffiche : les fichiers sont
         * prefixes de leur identifiant TMDB (« 237584 - Mojave »), utile au
         * rapprochement mais illisible sur une tuile.
         */
        val titreAffiche: String
            get() = titre.replace(RE_PREFIXE_ID, "").trim().ifBlank { titre }

        val embed: String get() = "$BASE/e/$code"
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
                        lib = o.optString("lib").ifBlank { "communaute" },
                        q = o.optInt("q", 0),
                        dossier = o.optString("dossier").trim(),
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

    fun tuileFilm(f: Fichier): TvShow =
        TvShow(id = "livehub::voecom::${f.code}", title = f.titreAffiche)
            .apply { providerName = "TV Hub" }

    /** Categories affichees dans l'entree « Partage de la communaute » : une par contributeur. */
    suspend fun categories(): List<Category> {
        if (!ACTIVE) return emptyList()
        val fichiers = tout()
        if (fichiers.isEmpty()) return emptyList()
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

    /** Serveur de lecture — identique a VoeLibrary : l'embed public suffit. */
    fun serveurDe(code: String, nom: String = "Communaute"): Video.Server =
        Video.Server(
            id = "livehub::voecom::$code",
            name = nom,
            src = "$BASE/e/$code",
        )
}
