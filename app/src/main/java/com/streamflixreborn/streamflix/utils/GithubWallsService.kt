package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-09-20 (user « le site de GitHub avec les images on va s'en servir comme repli
 * quand ce site web est en panne », puis « nous on consulte simplement leur GitHub,
 * on n'a rien à faire d'autre ») : source de secours pour la galerie de fonds
 * d'écran, quand l'API de Wallhaven ne répond plus.
 *
 * POURQUOI. Wallhaven rend 503 sur son API depuis plusieurs jours (verifie le
 * 2026-09-20 : `wallhaven.cc/api/v1/search` -> « 503 Service Temporarily
 * Unavailable », nginx). Le site principal est debout mais l'API est morte, donc la
 * galerie affichait une grille vide.
 *
 * LE DEPOT CHOISI : github.com/dharmx/walls. Mesure faite le 2026-09-20 en
 * consultant l'arbre du depot, compare a deux autres bibliotheques connues :
 *     dharmx/walls                 1 637 images   51 dossiers thematiques
 *     FrenzyExists/wallpapers        489 images   20 dossiers
 *     D3Ext/aesthetic-wallpapers     380 images    2 dossiers
 * dharmx gagne sur le volume, mais surtout ses fichiers sont NOMMES EN CLAIR
 * (« a_beach_with_waves_and_rocks.jpg », « a_black_and_white_drawing_of_a_person
 * _with_a_blindfold.png »). La barre de recherche continue donc de fonctionner en
 * repli, au lieu de ne servir qu'une liste figee.
 *
 * ON NE FABRIQUE RIEN DE NOTRE COTE. Pas d'index publie dans nx-data, pas de
 * redimensionneur intermediaire : on lit leur arbre et on sert leurs images. Un
 * seul appel a l'API GitHub par session (l'arbre complet en une fois, `recursive=1`),
 * garde en memoire ensuite. L'API GitHub sans jeton autorise 60 appels par heure et
 * par adresse IP : un appel par session, chez chaque utilisateur, ne s'en approche
 * pas.
 *
 * ⚠ PIEGE MESURE : le depot contient des fichiers ENORMES au milieu des autres —
 *   `nature/a.jpg` pese 42 Mo a lui seul, pour une moyenne de 1,8 Mo. Il n'y a pas
 *   de miniatures dans ce depot : la grille charge donc les images entieres. On
 *   ecarte tout ce qui depasse TAILLE_MAX, sinon une seule vignette peut manger
 *   plus de bande passante que toute la page.
 */
object GithubWallsService {
    private const val TAG = "GithubWalls"

    private const val DEPOT = "dharmx/walls"
    private const val BRANCHE = "main"
    private const val ARBRE = "https://api.github.com/repos/$DEPOT/git/trees/$BRANCHE?recursive=1"
    private const val BRUT = "https://raw.githubusercontent.com/$DEPOT/$BRANCHE/"

    /** Pas de miniatures dans le depot : au-dela, l'image coute trop cher a afficher. */
    private const val TAILLE_MAX = 6L * 1024 * 1024
    /** En dessous, c'est une icone ou un badge de README, pas un fond d'ecran. */
    private const val TAILLE_MIN = 20L * 1024

    private const val PAR_PAGE = 24

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Un fichier image du depot : chemin « nature/a_beach_with_waves_and_rocks.jpg ». */
    private data class Image(val chemin: String) {
        /** Le dossier fait office de theme : « nature », « anime », « minimal »... */
        val theme: String get() = chemin.substringBefore('/', "")
        /** Nom de fichier rendu lisible, pour la recherche par mot-cle. */
        val mots: String get() = chemin.substringAfterLast('/')
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .lowercase()
    }

    private val verrou = Mutex()
    @Volatile private var catalogue: List<Image>? = null

    /**
     * Lit l'arbre du depot UNE fois par session, puis garde la liste en memoire.
     * Le verrou evite que deux pages demandees coup sur coup fassent deux appels.
     */
    private suspend fun catalogue(): List<Image> {
        catalogue?.let { return it }
        return verrou.withLock {
            catalogue?.let { return it }
            val req = Request.Builder()
                .url(ARBRE)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Streamflix-Wallpaper/1.0")
                .build()
            val liste = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("GitHub HTTP ${resp.code}")
                val arbre = JSONObject(resp.body?.string().orEmpty()).optJSONArray("tree")
                    ?: throw Exception("arbre GitHub illisible")
                val tmp = ArrayList<Image>(2000)
                for (i in 0 until arbre.length()) {
                    val n = arbre.getJSONObject(i)
                    if (n.optString("type") != "blob") continue
                    val chemin = n.optString("path")
                    if (!chemin.matches(Regex(""".+\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE))) continue
                    val taille = n.optLong("size")
                    if (taille !in TAILLE_MIN..TAILLE_MAX) continue
                    // Une image a la racine n'a pas de theme : c'est une banniere de README.
                    if (!chemin.contains('/')) continue
                    tmp.add(Image(chemin))
                }
                tmp.sortedBy { it.chemin }
            }
            Log.d(TAG, "catalogue GitHub : ${liste.size} images retenues")
            catalogue = liste
            liste
        }
    }

    /**
     * Meme forme de reponse que Wallhaven pour que la galerie n'ait rien a savoir
     * de la source. `ratio` et `definition` sont ignores : un depot de fichiers ne
     * sait pas filtrer par proportion, et on ne va pas telecharger 1 637 images pour
     * les mesurer. Le champ `resolution` porte donc le THEME (« nature », « anime »),
     * ce qui reste une information utile sous la vignette.
     */
    suspend fun search(query: String, page: Int = 1): Result<WallhavenService.SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val tout = catalogue()
                val mots = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                val filtre = if (mots.isEmpty()) tout else tout.filter { img ->
                    val foin = img.theme + " " + img.mots
                    mots.all { foin.contains(it) }
                }
                val depart = ((page - 1).coerceAtLeast(0)) * PAR_PAGE
                val tranche = filtre.drop(depart).take(PAR_PAGE)
                val derniere = if (filtre.isEmpty()) 1
                               else ((filtre.size + PAR_PAGE - 1) / PAR_PAGE)
                Result.success(
                    WallhavenService.SearchResult(
                        wallpapers = tranche.map { img ->
                            val url = BRUT + img.chemin.split('/').joinToString("/") {
                                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                            }
                            WallhavenService.Wallpaper(
                                id = "gh_" + img.chemin.hashCode().toUInt().toString(16),
                                thumbUrl = url,   // pas de miniatures dans ce depot
                                fullUrl = url,
                                resolution = img.theme,
                                colors = emptyList()
                            )
                        },
                        currentPage = page,
                        lastPage = derniere,
                        total = filtre.size
                    )
                )
            } catch (e: Throwable) {
                Log.w(TAG, "repli GitHub KO : ${e.message}")
                Result.failure(e)
            }
        }
}
