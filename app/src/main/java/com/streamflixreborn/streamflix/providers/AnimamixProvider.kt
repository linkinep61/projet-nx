package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 2026-11-12 (user : « il vaut le coup d'être ajouté en provider », nommé « Animamix » : « les gens n'ont pas besoin de savoir ») — voiranime.rip.
 *   Site DISTINCT de VoirAnime (voir-anime.to) malgré le nom. Structure relevée dans Chrome :
 *   - accueil : rubriques `div.slider-rail#<id>` (cartes `.xcard` pour les épisodes,
 *     `.xcatalog` pour les animés), titre de rubrique `.section-title-label` ;
 *   - catalogue : `/catalogue/?type=Film|Série&langue=VF|VOSTFR&sort=recent&search=…&page=N`,
 *     cartes `a.card-base` (`.card-title`, `img.card-image`, `.badge-text` = Film / Série) ;
 *   - fiche : `/<slug>/` (h1, `.synopsis-content`, `.genre-link`, og:image) ;
 *     série → `a.season-card` (`.season-title`) vers `/<slug>/<saison>/` ;
 *     film → `const filmUrls = {"vostfr": "…", "vf": "…"}` dans la page ;
 *   - saison : `a.episode-card` (`data-langs`, `.episode-title`) vers
 *     `/<slug>/<saison>/episode-N/<langue>/` ;
 *   - épisode : `const readers = {"vostfr": [urls…], "vf": [urls…]}` dans la page.
 *   Identifiants internes : chemin relatif (`slug`, `slug/saison-1`, `slug/saison-1/episode-1`).
 */
object AnimamixProvider : Provider, ProgressiveServersProvider {

    private const val TAG = "Animamix"
    override val name = "Animamix"
    override val baseUrl = "https://voiranime.rip/"
    override val logo: String
        get() = "android.resource://${com.streamflixreborn.streamflix.BuildConfig.APPLICATION_ID}/drawable/logo_animamix"
    override val language = "fr"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private suspend fun charger(chemin: String): Document = withContext(Dispatchers.IO) {
        val url = if (chemin.startsWith("http")) chemin else baseUrl + chemin.trimStart('/')
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", baseUrl)
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("$name : HTTP ${r.code} pour ${url.removePrefix(baseUrl)}")
            Jsoup.parse(r.body?.string().orEmpty(), url)
        }
    }

    private fun absolu(src: String?): String? = when {
        src.isNullOrBlank() -> null
        src.startsWith("//") -> "https:$src"
        src.startsWith("http") -> src
        else -> baseUrl + src.trimStart('/')
    }

    /** "/slug/saison-1/episode-3/vostfr/" → "slug/saison-1/episode-3" ; "/slug/" → "slug". */
    private fun chemin(href: String, sansLangue: Boolean = true): String {
        var p = href.removePrefix(baseUrl).removePrefix("https://voiranime.rip").trim('/')
        if (sansLangue) p = p.removeSuffix("/vostfr").removeSuffix("/vf")
        return p
    }

    // ── Cartes ────────────────────────────────────────────────────────────────

    private fun estFilm(type: String?) = type?.trim()?.equals("Film", ignoreCase = true) == true

    private fun item(slug: String, titre: String, image: String?, film: Boolean): AppAdapter.Item =
        if (film) Movie(id = slug, title = titre, poster = absolu(image))
        else TvShow(id = slug, title = titre, poster = absolu(image))

    /** Carte du catalogue / de la recherche : `a.card-base`. */
    private fun carteCatalogue(a: Element): AppAdapter.Item? {
        val slug = chemin(a.attr("href")).substringBefore('/').ifBlank { return null }
        val titre = a.selectFirst(".card-title")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim() ?: return null
        val film = estFilm(a.selectFirst(".badge-text")?.text())
        return item(slug, titre, a.selectFirst("img.card-image, img")?.attr("src"), film)
    }

    /** Carte de l'accueil : `.xcard` (dernier épisode) ou `.xcatalog` (animé). */
    private fun carteAccueil(c: Element): AppAdapter.Item? {
        val a = c.selectFirst("a[href]") ?: return null
        val slug = chemin(a.attr("href")).substringBefore('/').ifBlank { return null }
        val titre = c.selectFirst(".xcard-title, .xcatalog-title")?.text()?.trim()
            ?: c.selectFirst("img")?.attr("alt")?.trim() ?: return null
        val type = c.selectFirst(".xbadge-type")?.text()
            ?: c.select(".xinfo-row").firstOrNull { it.selectFirst(".xinfo-label")?.text() == "Type" }
                ?.selectFirst(".xinfo-val")?.text()
        // Un « dernier épisode » renvoie toujours vers une série, même si l'animé est court.
        val film = estFilm(type) && !a.attr("href").contains("/episode-")
        return item(slug, titre, c.selectFirst("img")?.attr("src"), film)
    }

    // ── Accueil ───────────────────────────────────────────────────────────────

    override suspend fun getHome(): List<Category> {
        val doc = charger("")
        val categories = mutableListOf<Category>()
        // Titres et rubriques pris dans l'ordre de la page : chaque rubrique porte le dernier titre
        //   rencontré avant elle (le parent commun contient TOUS les titres de la page).
        var titreCourant: String? = null
        for (el in doc.select(".section-title-label, div.slider-rail[id]")) {
            if (el.hasClass("section-title-label")) { titreCourant = el.text().trim(); continue }
            val rail = el
            if (rail.id() in setOf("heroSlider", "continueWatchingSlider")) continue
            val titre = titreCourant ?: continue
            val items = rail.select(".xcard, .xcatalog").mapNotNull { carteAccueil(it) }
                .distinctBy { if (it is Movie) "m" + it.id else "s" + (it as TvShow).id }
            val nom = titre.lowercase().replaceFirstChar { it.uppercase() }
                .replace(Regex("\\bvostfr\\b"), "VOSTFR").replace(Regex("\\bvf\\b"), "VF")
            if (items.isNotEmpty()) categories += Category(name = nom, list = items)
        }
        // Bandeau du haut : les animés mis en avant (titres h2 de la page d'accueil).
        val vedettes = doc.select("#heroSlider a.btn-watch[href], #heroSlider a[href]")
            .map { chemin(it.attr("href")).substringBefore('/') }.filter { it.isNotBlank() }.distinct()
        val tous = categories.flatMap { it.list }
        // COPIES obligatoires : le carrousel change le type d'affichage de ses éléments ; partager les
        //   mêmes objets avec les rangées fait planter l'accueil (ViewPager2 « Pages must fill the
        //   whole ViewPager2 ») — vu à la 1re ouverture du profil, comme jadis sur Movix et Scans.
        val enTete = vedettes.mapNotNull { s -> tous.firstOrNull { (it as? TvShow)?.id == s || (it as? Movie)?.id == s } }
            .map { when (it) { is TvShow -> it.copy(); is Movie -> it.copy(); else -> it } }
        if (enTete.isNotEmpty()) categories.add(0, Category(name = Category.FEATURED, list = enTete))
        return categories
    }

    // ── Catalogue, recherche ──────────────────────────────────────────────────

    private suspend fun catalogue(params: String, page: Int): List<AppAdapter.Item> =
        charger("catalogue/?$params&page=$page").select("a.card-base").mapNotNull { carteCatalogue(it) }

    override suspend fun getMovies(page: Int): List<Movie> =
        catalogue("type=Film&sort=recent", page).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        catalogue("type=S%C3%A9rie&sort=recent", page).filterIsInstance<TvShow>()

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val direct = catalogue("search=" + java.net.URLEncoder.encode(query, "UTF-8"), page)
        if (direct.isNotEmpty()) return direct
        // La recherche du site est une correspondance EXACTE (vérifié : « Gintama » → 5 résultats,
        //   « Gintama Yoshiwara » → 0 car le titre est « Gintama : Yoshiwara… »). Repli : le mot le
        //   plus long, puis on garde les titres qui contiennent tous les mots tapés.
        fun norme(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
        val mots = norme(query).split(' ').filter { it.length >= 2 }
        if (mots.size < 2) return direct
        val pivot = mots.maxByOrNull { it.length } ?: return direct
        return catalogue("search=" + java.net.URLEncoder.encode(pivot, "UTF-8"), page).filter { item ->
            val t = norme(if (item is Movie) item.title else (item as TvShow).title)
            mots.all { t.contains(it) }
        }
    }

    // ── Fiches ────────────────────────────────────────────────────────────────

    private fun synopsis(doc: Document): String? =
        doc.selectFirst(".synopsis-content, .synopsis-text")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.substringAfter(" HD. ", "")?.trim()?.ifBlank { null }

    private fun genres(doc: Document): List<Genre> =
        doc.select(".genre-link").map { Genre(id = it.text().trim(), name = it.text().trim()) }
            .filter { it.name.isNotBlank() }.distinctBy { it.name }

    private fun affiche(doc: Document): String? =
        absolu(doc.selectFirst("meta[property=og:image]")?.attr("content"))

    override suspend fun getMovie(id: String): Movie {
        val doc = charger("$id/")
        return Movie(
            id = id,
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty(),
            overview = synopsis(doc),
            poster = affiche(doc),
            genres = genres(doc),
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = charger("$id/")
        val titre = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val jaquette = affiche(doc)
        val saisons = doc.select("a.season-card[href]").mapIndexed { i, a ->
            val nomSaison = a.selectFirst(".season-title")?.text()?.trim()
            val numero = Regex("""saison-(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            // 2026-11-12 (user : « mettre une petite jaquette sur les saisons ») : le site n'a pas
            //   d'image par saison (cartes texte seulement) → la jaquette de l'animé.
            Season(id = chemin(a.attr("href")), number = numero ?: (i + 1), title = nomSaison,
                poster = absolu(a.selectFirst("img")?.attr("src")) ?: jaquette)
        }
        return TvShow(
            id = id,
            title = titre,
            overview = synopsis(doc),
            poster = affiche(doc),
            genres = genres(doc),
            seasons = saisons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val doc = charger("$seasonId/")
        // 2026-11-13 (user : « pas de jaquette sur les épisodes, tout est gris ») : le site ne donne
        //   AUCUNE image par épisode (cartes texte seules). On prend l'illustration de la page de
        //   saison (og:image, format paysage) pour tous ses épisodes.
        val vignette = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
        return doc.select("a.episode-card[href]").mapNotNull { a ->
            val id = chemin(a.attr("href"))
            val n = Regex("""episode-(\d+)""").find(id)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val langues = a.attr("data-langs").trim().uppercase().replace(" ", " / ")
            Episode(id = id, number = n, poster = vignette,
                title = (a.selectFirst(".episode-title")?.text()?.trim() ?: "Episode $n") +
                    if (langues.isNotBlank()) " ($langues)" else "")
        }.distinctBy { it.id }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id)

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)

    // ── Serveurs ──────────────────────────────────────────────────────────────

    /** Lit `const <nom> = {...};` dans le HTML brut d'une page. */
    private fun objetJs(html: String, nom: String): JSONObject? {
        val debut = Regex("""const\s+$nom\s*=\s*""").find(html) ?: return null
        return runCatching {
            org.json.JSONTokener(html.substring(debut.range.last + 1)).nextValue() as? JSONObject
        }.getOrNull()
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        // Par langue : liste d'adresses de lecteurs (VF d'abord, l'appli est francophone).
        val parLangue = linkedMapOf<String, List<String>>()
        if (videoType is Video.Type.Movie && !id.contains("/")) {
            val html = charger("$id/").outerHtml()
            objetJs(html, "filmUrls")?.let { o ->
                for (l in listOf("vf", "vostfr")) o.optString(l).takeIf { it.startsWith("http") }?.let { parLangue[l] = listOf(it) }
            }
        } else {
            // La page d'un épisode contient les lecteurs de TOUTES ses langues ; on essaie
            //   l'adresse VOSTFR puis VF (certains épisodes n'existent qu'en VF).
            val html = listOf("vostfr", "vf").firstNotNullOfOrNull { l ->
                runCatching { charger("$id/$l/").outerHtml() }.getOrNull()?.takeIf { it.contains("const readers") }
            }
            objetJs(html.orEmpty(), "readers")?.let { o ->
                for (l in listOf("vf", "vostfr")) {
                    val arr = o.optJSONArray(l) ?: continue
                    parLangue[l] = (0 until arr.length()).map { arr.optString(it) }.filter { it.startsWith("http") }
                }
            }
        }
        val serveurs = parLangue.flatMap { (langue, urls) ->
            urls.mapIndexed { i, url ->
                val hote = runCatching { java.net.URL(url).host.removePrefix("www.") }.getOrDefault("lecteur")
                Video.Server(id = "$langue-$i-$url", name = "${langue.uppercase()} · $hote", src = url)
            }
        }.distinctBy { it.src }
        Log.d(TAG, "getServers $id → ${serveurs.size} lecteur(s) " + parLangue.mapValues { it.value.size })
        serveurs
    }

    /**
     * 2026-11-13 (user : « il n'appelle pas les autres providers animés ») — le lecteur ne lance
     * les sources de SECOURS que sur le chemin « progressif » (journal : `path provider=Animamix
     * progressive=false`, puis aucun secours). On émet donc nos serveurs par ce chemin, comme
     * VoirAnime et French-Anime : le lecteur y ajoute ensuite les secours tout seul.
     */
    override fun getServersProgressive(id: String, videoType: Video.Type): kotlinx.coroutines.flow.Flow<List<Video.Server>> =
        kotlinx.coroutines.flow.channelFlow {
            try {
                val serveurs = withContext(Dispatchers.IO) { getServers(id, videoType) }
                if (serveurs.isNotEmpty()) send(serveurs)
            } catch (e: Exception) { Log.w(TAG, "serveurs natifs KO : ${e.message}") }
        }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)
}
