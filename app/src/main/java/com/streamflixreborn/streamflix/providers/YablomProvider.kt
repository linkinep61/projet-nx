package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * YablomProvider — backup NATIF par TITRE (2026-08-06).
 *
 * Site : https://yablom.com — petit catalogue FR, FILMS UNIQUEMENT (pas de séries).
 * Repéré via le dépôt Cloudstream `Kraptor123/cs-Karma` (plugin Yablom v4), seul provider
 * français que nous n'avions pas après le balayage complet des 26 dépôts officiels.
 *
 * Chaîne vérifiée en direct AVANT d'écrire ce fichier (règle du projet) :
 *   GET /euvcw7/api_search.php?searchword=<titre>&offset=0&limit=20&folder=euvcw7&pr=yablom
 *       → 200, JSON { films:[{title,poster,link}], hasMore }        (8 résultats sur "spider")
 *   GET <link>  (ex /euvcw7/b/yablom/48059968)
 *       → page HTML contenant <iframe src="https://sharecloudy.com/iframe/1V4HHtvvKr">
 *
 * ⚠ Le cookie `g=true` est OBLIGATOIRE sur toutes les requêtes : sans lui le site renvoie
 *   sa page d'accueil au lieu du JSON. C'est le seul garde-fou, il n'y a pas de Cloudflare.
 *
 * Lecture : aucune `getVideo` ici. La src est une iframe sharecloudy, et le `else` de
 *   [BackupRegistry.getVideo] retombe sur `Extractor.extract` → `ShareCloudyExtractor`,
 *   qui existe déjà dans le projet. Rien à brancher de plus.
 */
object YablomProvider {
    private const val TAG = "YablomProvider"
    private const val BASE = "https://yablom.com"
    private const val FOLDER = "euvcw7"
    private const val AUTH_COOKIE = "g=true"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0"

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class YablomFilm(
        val title: String? = null,
        val poster: String? = null,
        val link: String? = null,
    )

    private data class YablomSearch(
        val films: List<YablomFilm>? = null,
        val hasMore: Boolean? = null,
    )

    /** `<iframe src="https://sharecloudy.com/iframe/XXXX">` — seul lecteur servi par le site. */
    private val IFRAME_REGEX = Regex("""<iframe[^>]*src="([^"]*sharecloudy[^"]*)"""", RegexOption.IGNORE_CASE)

    /** Année entre parenthèses en fin de titre : « Spider-Man : No Way Home (2022) ». */
    private val YEAR_REGEX = Regex("""\((\d{4})\)""")

    /**
     * Backup par titre. FILMS uniquement — le site n'a pas de séries, on sort tout de suite
     * sur un [Video.Type.Episode] plutôt que de faire une recherche qui ne rendra rien.
     *
     * @param year si fourni, un écart de plus d'un an écarte le résultat (les rééditions et
     *   versions longues décalent parfois l'année d'un cran).
     */
    suspend fun fetchYablomBackup(
        title: String,
        year: Int? = null,
        videoType: Video.Type? = null,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        if (videoType is Video.Type.Episode) return@withContext emptyList()

        val film = rechercher(title, year) ?: return@withContext emptyList()
        val lien = film.link?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val pageUrl = if (lien.startsWith("http")) lien else BASE + (if (lien.startsWith("/")) lien else "/$lien")

        val html = get(pageUrl, referer = "$BASE/$FOLDER/home/yablom") ?: return@withContext emptyList()
        val iframe = IFRAME_REGEX.find(html)?.groupValues?.get(1)
        if (iframe.isNullOrBlank()) {
            Log.d(TAG, "'${film.title}' : page trouvée mais aucune iframe sharecloudy")
            return@withContext emptyList()
        }

        // L'id de la fiche sert de suffixe : deux films différents ne peuvent pas se
        //   déduplicquer mutuellement dans le picker (leçon Wiflix du 31/07).
        val idFiche = pageUrl.trimEnd('/').substringAfterLast('/')
        Log.i(TAG, "MATCH '${film.title}' → iframe ShareCloudy")
        listOf(
            Video.Server(
                id = "yablom_$idFiche",
                name = "Yablom — ShareCloudy",
                src = if (iframe.startsWith("http")) iframe else "https:$iframe",
            )
        )
    }

    /** Recherche puis choisit le meilleur candidat, ou null si aucun ne correspond vraiment. */
    private fun rechercher(title: String, year: Int?): YablomFilm? {
        val q = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/$FOLDER/api_search.php?searchword=$q&offset=0&limit=20&folder=$FOLDER&pr=yablom"
        val body = get(url, referer = "$BASE/$FOLDER/home/yablom", ajax = true) ?: return null

        val films = try {
            gson.fromJson(body, YablomSearch::class.java)?.films
        } catch (e: Exception) {
            Log.e(TAG, "JSON illisible : ${e.message}"); null
        } ?: return null

        val cible = normaliser(title)
        return films.firstOrNull { f ->
            val brut = f.title ?: return@firstOrNull false
            val anneeSite = YEAR_REGEX.find(brut)?.groupValues?.get(1)?.toIntOrNull()
            // Le titre du site porte l'année et parfois une mention (« - Version longue ») :
            //   on compare sur le titre nettoyé de ces deux éléments.
            val nom = normaliser(YEAR_REGEX.replace(brut, "").substringBefore(" - Version"))
            val titreOk = nom == cible || nom.startsWith("$cible ") || cible.startsWith("$nom ")
            val anneeOk = year == null || anneeSite == null || kotlin.math.abs(anneeSite - year) <= 1
            titreOk && anneeOk
        }
    }

    /** Accents retirés, ponctuation ramenée à des espaces, minuscules — comparaison stable. */
    private fun normaliser(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun get(url: String, referer: String, ajax: Boolean = false): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("Cookie", AUTH_COOKIE)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Referer", referer)
            .apply { if (ajax) header("X-Requested-With", "XMLHttpRequest") }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "GET → HTTP ${resp.code}"); null }
            else resp.body?.string()
        }
    } catch (e: Exception) {
        Log.e(TAG, "GET échoué : ${e.message}"); null
    }
}
