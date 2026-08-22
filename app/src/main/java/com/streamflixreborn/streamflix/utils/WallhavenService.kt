package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 2026-06-09 (user "une API avec autant d'images ça doit être sympa et surtout
 * en 4K") : intégration de Wallhaven.cc, une API publique de wallpapers HD.
 *
 * Pas de clé API requise pour la lecture, juste un GET HTTP. Le service
 * supporte la recherche par mot-clé, la pagination et le filtrage SFW.
 *
 * Endpoint principal : https://wallhaven.cc/api/v1/search
 * Doc : https://wallhaven.cc/help/api
 */
object WallhavenService {
    private const val TAG = "WallhavenService"
    private const val API_BASE = "https://wallhaven.cc/api/v1/search"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    data class Wallpaper(
        val id: String,
        val thumbUrl: String,       // URL de la miniature (~300x200)
        val fullUrl: String,        // URL de l'image HD (4K+)
        val resolution: String,     // "1920x1080" etc.
        val colors: List<String>    // palette dominante (hex)
    )

    data class SearchResult(
        val wallpapers: List<Wallpaper>,
        val currentPage: Int,
        val lastPage: Int,
        val total: Int
    )

    /**
     * Recherche des wallpapers.
     * @param query texte libre (peut être vide → top wallpapers)
     * @param page pagination (1-based)
     * @param ratio "16x9" pour TV, "9x16" pour mobile portrait, null = tout
     */
    suspend fun search(
        query: String,
        page: Int = 1,
        ratio: String? = null,
        /** Définition minimale demandée, « 3840x2160 ». Null = valeur par défaut du profil. */
        definition: String? = null,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        try {
            // categories : general(1) + anime(1) + people(0) = "110"
            // purity : SFW(1) + sketchy(0) + NSFW(0) = "100"
            // sorting : toplist (par défaut = relevance si query, sinon top)
            val urlBuilder = StringBuilder(API_BASE)
                .append("?categories=110")
                .append("&purity=100")
                .append("&sorting=")
                .append(if (query.isBlank()) "toplist" else "relevance")
                .append("&page=").append(page)
            // ══════════════════════════════════════════════════════════════════════
            // TRI PAR APPAREIL — 2026-08-22 (user « les fonds d'écran sont tout le
            //   temps zoomés malgré la taille, même sur TV »)
            //
            // MESURE. Le fond est affiché en centerCrop : l'image est agrandie
            // jusqu'à remplir l'écran, le reste est coupé. J'ai chiffré ce qui est
            // perdu sur un écran de téléphone 1080×2400 :
            //     aucun filtre (ce qu'on faisait)  → 63 % de l'image perdue
            //     ratios=portrait                  → 35 %
            //     ratios=9x16,9x18,9x20            → 19 %
            // « portrait » chez Wallhaven ne veut pas dire « fait pour un
            // téléphone » mais « plus haut que large » : ce sont surtout des
            // illustrations en 2:3 et 3:4, alors qu'un téléphone est en 9:20.
            // Seules les vraies proportions de téléphone donnaient un cadrage
            // propre — mais le catalogue tombait à 6 474 fonds contre 66 889, et le
            // user a préféré garder le choix large : le filtre mobile a donc été
            // RETIRÉ. Ce paragraphe reste ici pour que personne ne refasse la
            // mesure en croyant découvrir quelque chose.
            //
            // Côté TV, on ne demandait qu'une DÉFINITION (`atleast`) sans aucune
            // proportion : les 21:9 et les formats verticaux passaient, et se
            // faisaient massacrer au recadrage sur un écran 16:9. D'où le « trop
            // zoomé » alors que la définition était excellente. On remet une
            // contrainte de forme, mais avec DEUX proportions.
            //
            // ⚠ Le piège du 2026-08-04 (« les thèmes ne renvoient rien ») venait de
            //   `ratios=16x9` SEUL, trop strict. Deux garde-fous ici : deux
            //   proportions au lieu d'une (243 907 fonds au lieu de 202 593 ;
            //   « one piece » garde 640 résultats), et le repli automatique plus
            //   bas — si la recherche filtrée ne renvoie RIEN, on la relance sans
            //   contrainte plutôt que d'afficher une grille vide.
            // ══════════════════════════════════════════════════════════════════════
            //
            // DÉFINITION SUIVANT L'ÉCRAN — 2026-08-22 (user « ensuite réparer la
            //   taille pour les écrans de télé »). On demandait 1920×1080 en dur.
            //   Sur une télé 4K, un fond exactement 1920×1080 est agrandi deux fois
            //   → flou, alors que le catalogue a de quoi faire :
            //       16x9/16x10 ≥ 1920×1080 → 243 907 fonds
            //       16x9/16x10 ≥ 2560×1440 →  84 479
            //       16x9/16x10 ≥ 3840×2160 →  46 579  (21 784 en animé)
            //   L'appelant transmet donc la définition RÉELLE de l'écran. Une box
            //   qui rend son interface en 1080p demandera 1080p — inutile de tirer
            //   du 4K qui serait de toute façon réduit à l'affichage.
            //
            // ⚠ MOBILE : volontairement AUCUNE contrainte (ratio null). Un filtre
            //   portrait avait été essayé le 22/08 puis retiré à la demande du user
            //   — voir le commentaire dans WallhavenGalleryActivity.fetchPage.
            val contrainte = when (ratio) {
                null -> ""
                "16x9" -> "&ratios=16x9,16x10&atleast=${definition ?: "1920x1080"}"
                else -> "&ratios=$ratio"
            }
            urlBuilder.append(contrainte)
            if (query.isNotBlank()) {
                urlBuilder.append("&q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            }

            val req = Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", "Streamflix-Wallpaper/1.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${resp.code}: ${resp.message}")
                    )
                }
                val body = resp.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty body")
                )
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return@withContext Result.failure(
                    Exception("No 'data' field in response")
                )
                val list = mutableListOf<Wallpaper>()
                for (i in 0 until data.length()) {
                    val w = data.getJSONObject(i)
                    val thumbs = w.optJSONObject("thumbs")
                    val colorsArr = w.optJSONArray("colors")
                    val colors = mutableListOf<String>()
                    if (colorsArr != null) for (j in 0 until colorsArr.length()) {
                        colors.add(colorsArr.getString(j))
                    }
                    list.add(
                        Wallpaper(
                            id = w.optString("id"),
                            thumbUrl = thumbs?.optString("large") ?: w.optString("path"),
                            fullUrl = w.optString("path"),
                            resolution = w.optString("resolution", ""),
                            colors = colors
                        )
                    )
                }
                // Repli : une recherche filtrée qui ne renvoie RIEN vaut moins qu'une
                //   grille un peu mal cadrée. On relance sans contrainte de forme.
                //   `ratio = null` donne une contrainte vide → pas de seconde récursion.
                if (list.isEmpty() && contrainte.isNotEmpty()) {
                    Log.d(TAG, "0 résultat avec $contrainte → nouvelle tentative sans contrainte")
                    return@withContext search(query, page, null)
                }

                val meta = json.optJSONObject("meta")
                Result.success(
                    SearchResult(
                        wallpapers = list,
                        currentPage = meta?.optInt("current_page", page) ?: page,
                        lastPage = meta?.optInt("last_page", page) ?: page,
                        total = meta?.optInt("total", list.size) ?: list.size
                    )
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "search failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Télécharge l'image HD dans le cache local de l'app et retourne le File.
     * Le caller peut ensuite passer son Uri à AppearanceManager.setWallpaperUri.
     */
    suspend fun downloadToLocal(ctx: Context, wallpaper: Wallpaper): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(ctx.filesDir, "wallhaven").apply { mkdirs() }
                val ext = wallpaper.fullUrl.substringAfterLast(".").take(4)
                val outFile = File(dir, "wp_${wallpaper.id}.${ext}")
                if (outFile.exists() && outFile.length() > 0) return@withContext outFile

                val req = Request.Builder()
                    .url(wallpaper.fullUrl)
                    .header("User-Agent", "Streamflix-Wallpaper/1.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    FileOutputStream(outFile).use { fos ->
                        body.byteStream().copyTo(fos)
                    }
                }
                outFile
            } catch (e: Throwable) {
                Log.w(TAG, "downloadToLocal failed: ${e.message}")
                null
            }
        }
}
