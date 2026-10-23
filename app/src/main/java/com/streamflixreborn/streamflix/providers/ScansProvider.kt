package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
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
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-10-20 (user : « un nouveau profil Scans dans l'onglet Animés ») — lecture de mangas / manhwas.
 * Source : sushiscan.net (thème WordPress « MangaReader »), relevé en direct dans Chrome :
 *
 *  • CATALOGUE : /catalogue/?order=popular|update|latest&type=manga|manhwa|…&genre[]=<id>&page=N
 *      cartes `.bs .bsx a` (href /catalogue/<slug>/, title, img, `.epxs` = dernier chapitre).
 *  • RECHERCHE : /page/N/?s=<texte>  (mêmes cartes).
 *  • FICHE : /catalogue/<slug>/ → h1.entry-title, .thumb img, description, .mgen a (genres),
 *      .imptdt (Statut, Type, Année, Auteur), #chapterlist li a (du plus récent au plus ancien).
 *  • CHAPITRE : /<slug-chapitre>/ → `ts_reader.run({ sources:[{ images:[…] }], prevUrl, nextUrl })`
 *      = toutes les pages du chapitre (webp/jpg sur un sous-domaine de sushiscan.net, sans jeton).
 *
 * Les pages ne sont PAS des vidéos : un « épisode » (id `scan::…`) n'ouvre pas le lecteur vidéo mais
 * ScanReaderActivity (cf. redirection au début des fragments lecteur). Provider « séparé » : jamais
 * mélangé à la recherche globale, aux genres communs ni aux serveurs de secours.
 */
object ScansProvider : Provider {

    private const val TAG = "Scans"
    const val BASE = "https://sushiscan.net"
    const val ID_PREFIX = "scan::"

    override val baseUrl = "$BASE/"
    override val name = "Scans"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_scans"
    override val language = "fr"

    /** Genres du site (id du filtre `genre[]`) — relevés le 2026-10-20. */
    private val GENRES = linkedMapOf(
        "Action" to 3, "Aventure" to 12, "Romance" to 26, "Fantasy" to 32, "Comédie" to 13,
        "Drame" to 4, "Fantastique" to 5, "Isekai" to 639, "Horreur" to 6, "Thriller" to 79,
        "Science-Fiction" to 37, "Surnaturel" to 9, "Mystère" to 8, "Psychologique" to 20,
        "School-Life" to 16, "Slice of Life" to 28, "Sport" to 18, "Historique" to 24,
        "Nekketsu" to 764, "Tournois" to 305, "Tragique" to 10, "Mature" to 7, "Polar" to 3251,
        "Science-Fantasy" to 3443, "Histoires courtes" to 93, "Biographique" to 165,
        "Gekiga" to 3727, "Furyo" to 2455, "Shôjo-aï" to 173, "Shônen-aï" to 599, "Yonkoma" to 819,
    )
    private val TYPES = linkedMapOf(
        "Manga" to "manga", "Manhwa (Corée)" to "manhwa", "Manhua (Chine)" to "manhua",
        "Comics" to "comic", "Bande dessinée" to "bande-dessinée", "Global manga" to "global-manga",
    )

    // ───────────────────────── réseau ─────────────────────────

    val headersImages: Map<String, String>
        get() = mapOf("Referer" to "$BASE/", "User-Agent" to NetworkClient.USER_AGENT)

    /** En-têtes d'une page selon le site qui la sert. */
    fun headersPour(url: String): Map<String, String> =
        if (url.contains("anime-sama")) mapOf("Referer" to "$AS_BASE/", "User-Agent" to NetworkClient.USER_AGENT)
        else if (url.contains("pstatic.net") || url.contains("webtoons.com")) mapOf("Referer" to "$WT_BASE/", "User-Agent" to NetworkClient.USER_AGENT)
        else headersImages

    private suspend fun getDoc(url: String): Document = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .header("Referer", "$BASE/")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code} sur $url")
            Jsoup.parse(r.body?.string() ?: "", url)
        }
    }

    private val cacheListes = ConcurrentHashMap<String, Pair<Long, List<TvShow>>>()
    private const val CACHE_MS = 30 * 60 * 1000L

    /** Cartes d'une page de liste (catalogue / recherche). */
    private suspend fun liste(url: String): List<TvShow> {
        cacheListes[url]?.let { (t, l) -> if (System.currentTimeMillis() - t < CACHE_MS) return l }
        val doc = getDoc(url)
        val l = doc.select(".bs .bsx a[href*=/catalogue/]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/catalogue/").trim('/').takeIf { it.isNotBlank() && it != "list-mode" }
                ?: return@mapNotNull null
            val img = a.selectFirst("img")
            val poster = (img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")).orEmpty()
            TvShow(
                id = "$ID_PREFIX$slug",
                title = (a.attr("title").ifBlank { a.selectFirst(".tt")?.text().orEmpty() }).trim(),
                poster = poster,
                banner = poster,
                quality = a.selectFirst(".epxs")?.text()?.trim()?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.id }
        if (l.isNotEmpty()) cacheListes[url] = System.currentTimeMillis() to l
        return l
    }

    private fun catalogue(order: String = "popular", page: Int = 1, genre: Int? = null, type: String? = null): String {
        val q = StringBuilder("$BASE/catalogue/?order=$order&page=$page")
        genre?.let { q.append("&genre%5B%5D=").append(it) }
        type?.let { q.append("&type=").append(URLEncoder.encode(it, "UTF-8")) }
        return q.toString()
    }

    // ───────────────────────── Anime-Sama (2ᵉ site du profil) ─────────────────────────
    //
    // 2026-10-20 (user : « un seul profil pour les 2 sites ») — relevé dans Chrome :
    //  • CATALOGUE : /catalogue/?type[]=Scans&page=N (&search=…, &genre[]=Nom) → a.as-carte-lien
    //      (href /catalogue/<slug>/, img, h3.as-carte-titre). 24 pages × 48 ≈ 1 150 œuvres.
    //  • FICHE : /catalogue/<slug>/ → #titreOeuvre, #imgOeuvre, #synopsisText, et les variantes de
    //      scans dans panneauScan("Scans (couleur)", "scan/vf"), panneauScan("…", "scan_noir-et-blanc/vf").
    //  • VARIANTE : /catalogue/<slug>/<variante>/ → #titreOeuvre = NOM DES FICHIERS (« One Piece Couleur »).
    //  • CHAPITRES : /s2/scans/get_nb_chap_et_img.php?oeuvre=<nom> → {"1":55,"2":23,…} (pages par chapitre).
    //  • PAGES : /s2/scans/<nom>/<chapitre>/<1..n>.jpg
    // Le site est derrière Cloudflare : on passe par les fonctions d'AnimeSamaProvider (bypass déjà en place).

    private const val AS_BASE = "https://anime-sama.to"
    private const val AS_PREFIX = "${ID_PREFIX}as::"
    private val AS_GENRES = listOf("Action", "Aventure", "Romance", "Fantasy", "Isekai", "Comédie", "Shônen",
        "Seinen", "Shôjo", "Horreur", "Drame", "Mystère", "Psychologique", "Surnaturel", "School Life",
        "Slice of Life", "Sport", "Arts martiaux", "Magie", "Réincarnation / Transmigration", "Super pouvoirs",
        "Démons", "Monstres", "Science-fiction", "Historique", "Josei", "Harem", "Mechas", "Post-apocalyptique")

    fun estAnimeSama(id: String) = id.startsWith(AS_PREFIX)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun asListe(url: String): List<TvShow> {
        cacheListes[url]?.let { (t, l) -> if (System.currentTimeMillis() - t < CACHE_MS) return l }
        val doc = AnimeSamaProvider.documentPourScans(url)
        val l = doc.select("a.as-carte-lien[href*=/catalogue/]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/catalogue/").trim('/').substringBefore("/")
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img = a.selectFirst(".as-carte-image img")?.attr("src")
            TvShow(
                id = "$AS_PREFIX$slug",
                title = a.selectFirst(".as-carte-titre")?.text()?.trim().orEmpty(),
                poster = img, banner = img, quality = "Anime-Sama",
            )
        }.distinctBy { it.id }
        if (l.isNotEmpty()) cacheListes[url] = System.currentTimeMillis() to l
        return l
    }

    private fun asCatalogue(page: Int = 1, genre: String? = null, recherche: String? = null): String {
        val q = StringBuilder("$AS_BASE/catalogue/?type%5B%5D=Scans&page=$page")
        genre?.let { q.append("&genre%5B%5D=").append(enc(it)) }
        recherche?.let { q.append("&search=").append(enc(it)) }
        return q.toString()
    }

    private suspend fun asFiche(slug: String): TvShow {
        val doc = AnimeSamaProvider.documentPourScans("$AS_BASE/catalogue/$slug/")
        val html = doc.outerHtml()
        val titre = doc.selectFirst("#titreOeuvre")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("#imgOeuvre")?.attr("src")
        val synopsis = doc.selectFirst("#synopsisText")?.text()?.trim()
        val variantes = Regex("""panneauScan\("([^"]+)",\s*"([^"]+)"\)""").findAll(html)
            .map { it.groupValues[1] to it.groupValues[2].trim('/') }
            .filter { (nom, chemin) -> nom != "nom" && chemin.startsWith("scan") }
            .distinctBy { it.second }.toList()
        val genres = doc.select("a[href*=genre]").map { it.text().trim() }.filter { it in AS_GENRES }.distinct()
            .map { Genre(id = "as:g:$it", name = it) }
        return TvShow(
            id = "$AS_PREFIX$slug",
            title = titre,
            overview = synopsis,
            quality = "Anime-Sama",
            poster = cover, banner = cover,
            genres = genres,
            seasons = variantes.mapIndexed { i, (nom, chemin) ->
                Season(id = "$AS_PREFIX$slug::$chemin", number = i + 1, title = nom, poster = cover)
            },
        )
    }

    /** Nom des fichiers d'une variante + pages par chapitre (mis en cache 30 min). */
    private val asVariantes = ConcurrentHashMap<String, Pair<Long, Pair<String, LinkedHashMap<String, Int>>>>()

    private suspend fun asChapitres(slug: String, chemin: String): Pair<String, LinkedHashMap<String, Int>> {
        val cle = "$slug::$chemin"
        asVariantes[cle]?.let { (t, v) -> if (System.currentTimeMillis() - t < CACHE_MS) return v }
        val page = AnimeSamaProvider.documentPourScans("$AS_BASE/catalogue/$slug/$chemin/")
        val nom = page.selectFirst("#titreOeuvre")?.text()?.trim().orEmpty()
        val json = JSONObject(AnimeSamaProvider.textePourScans("$AS_BASE/s2/scans/get_nb_chap_et_img.php?oeuvre=${enc(nom)}"))
        val cles = json.keys().asSequence().toList()
            .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.MAX_VALUE }, { it }))
        val chap = LinkedHashMap<String, Int>()
        cles.forEach { chap[it] = json.optInt(it, 0) }
        val v = nom to chap
        asVariantes[cle] = System.currentTimeMillis() to v
        return v
    }

    private suspend fun asChargerChapitre(id: String): Chapitre {
        // scan::as::<slug>::<chemin>::<chapitre>
        val reste = id.removePrefix(AS_PREFIX).split("::")
        val slug = reste[0]; val chemin = reste[1]; val chap = reste[2]
        val (nom, chapitres) = asChapitres(slug, chemin)
        val n = chapitres[chap] ?: 0
        val images = (1..n).map { "$AS_BASE/s2/scans/${enc(nom)}/${enc(chap)}/$it.jpg" }
        val cles = chapitres.keys.toList()
        val i = cles.indexOf(chap)
        fun idDe(c: String?) = c?.let { "$AS_PREFIX$slug::$chemin::$it" }
        return Chapitre(
            id = id, titre = "$nom — Chapitre $chap", images = images,
            precedent = idDe(cles.getOrNull(i - 1)), suivant = idDe(cles.getOrNull(i + 1)),
        )
    }


    // ───────────────────────── Webtoon (3ᵉ site du profil) ─────────────────────────
    //
    // 2026-10-21 (user : « si c'est possible et qu'on peut agrémenter de contenu, pourquoi pas ») —
    //   plateforme OFFICIELLE (webtoons.com/fr), relevé dans Chrome. On ne prend QUE ce que le site
    //   web propose librement : les épisodes listés sur la page de la série (le Fast Pass payant
    //   n'y figure pas et n'est jamais demandé).
    //  • CATALOGUE : /fr/genres (≈ 420 séries, toutes catégories) ; /fr/genres/<g>?sortOrder=MANA ;
    //      /fr/originals (≈ 130). Cartes a[href*=/list?title_no=] (data-title-no, img, strong.title).
    //  • RECHERCHE : /fr/search?keyword=<texte> → a._card_item (mêmes cartes).
    //  • SÉRIE : /fr/x/x/list?title_no=N (redirige vers l'adresse canonique ; la pagination
    //      &page=P ne marche QUE sur l'adresse canonique) → h1.subj, p.summary, og:image,
    //      li._episodeItem[data-episode-no] (10 par page, du plus récent au plus ancien).
    //  • ÉPISODE : /fr/x/x/x/viewer?title_no=N&episode_no=E (redirige aussi) → #_imageList img[data-url]
    //      (JPEG 800 px de large sur webtoon-phinf.pstatic.net, Referer webtoons.com exigé),
    //      a._prevEpisode / a._nextEpisode.

    private const val WT_BASE = "https://www.webtoons.com"
    private const val WT_PREFIX = "${ID_PREFIX}wt::"
    private val WT_GENRES = linkedMapOf(
        "Romance" to "romance", "Fantasy" to "fantasy", "Drame" to "drama", "Action" to "action",
        "Comédie" to "comedy", "Thriller" to "thriller", "Horreur" to "horror", "Tranche de vie" to "slice_of_life",
        "Super-héros" to "super_hero", "Science-fiction" to "sf", "Sport" to "sports", "Informatif" to "tiptoon",
        "Création française" to "local",
    )

    fun estWebtoon(id: String) = id.startsWith(WT_PREFIX)

    /** Document + adresse finale (après redirection). */
    private suspend fun wtDoc(url: String): Pair<Document, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .header("Referer", "$WT_BASE/fr/")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code} sur $url")
            val fin = r.request.url.toString()
            Jsoup.parse(r.body?.string() ?: "", fin) to fin
        }
    }

    private suspend fun wtListe(url: String): List<TvShow> {
        cacheListes[url]?.let { (t, l) -> if (System.currentTimeMillis() - t < CACHE_MS) return l }
        val doc = wtDoc(url).first
        val l = doc.select("a[href*=/list?title_no=]").mapNotNull { a ->
            val no = a.attr("data-title-no").ifBlank { Regex("title_no=(\\d+)").find(a.attr("href"))?.groupValues?.get(1).orEmpty() }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val titre = a.selectFirst("strong.title, .title")?.text()?.trim().orEmpty()
            if (titre.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")?.attr("src")
            TvShow(id = "$WT_PREFIX$no", title = titre, poster = img, banner = img, quality = "Webtoon")
        }.distinctBy { it.id }
        if (l.isNotEmpty()) cacheListes[url] = System.currentTimeMillis() to l
        return l
    }

    private fun wtGenre(g: String) = "$WT_BASE/fr/genres/$g?sortOrder=MANA"

    private data class WtSerie(val tv: TvShow, val episodes: List<Pair<Int, String>>)
    private val wtSeries = ConcurrentHashMap<String, Pair<Long, WtSerie>>()

    private suspend fun wtSerie(no: String): WtSerie = coroutineScope {
        wtSeries[no]?.let { (t, s) -> if (System.currentTimeMillis() - t < CACHE_MS) return@coroutineScope s }
        val (doc, fin) = wtDoc("$WT_BASE/fr/x/x/list?title_no=$no")
        val base = fin.substringBefore("?")
        fun episodesDe(d: Document) = d.select("li._episodeItem").mapNotNull { li ->
            val n = li.attr("data-episode-no").toIntOrNull() ?: return@mapNotNull null
            n to (li.selectFirst(".subj span")?.text()?.trim()?.ifBlank { null } ?: "Ép. $n")
        }
        val page1 = episodesDe(doc)
        val dernier = page1.maxOfOrNull { it.first } ?: 0
        val nbPages = ((dernier + 9) / 10 + 1).coerceIn(1, 60)
        val autres = (2..nbPages).map { p ->
            async { runCatching { episodesDe(wtDoc("$base?title_no=$no&page=$p").first) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
        val episodes = (page1 + autres).distinctBy { it.first }.sortedBy { it.first }
        val titre = doc.selectFirst("h1.subj, h1")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val genre = doc.selectFirst("h2.genre, .genre")?.text()?.trim()
        val auteur = doc.selectFirst(".author_area")?.ownText()?.trim()?.trimEnd(',')?.ifBlank { null }
            ?: doc.selectFirst(".author_area")?.text()?.substringBefore("Info")?.trim()
        val tv = TvShow(
            id = "$WT_PREFIX$no",
            title = titre,
            overview = listOfNotNull(doc.selectFirst("p.summary")?.text()?.trim(),
                "Webtoon officiel · ${episodes.size} épisodes gratuits").joinToString("\n\n"),
            quality = "Webtoon",
            poster = cover, banner = cover,
            genres = listOfNotNull(genre?.let { g ->
                WT_GENRES.entries.firstOrNull { it.key.equals(g, true) }?.let { Genre(id = "wt:g:${it.value}", name = it.key) }
            }),
            directors = listOfNotNull(auteur?.let { People(id = "${ID_PREFIX}auteur:$it", name = it) }),
            seasons = listOf(Season(id = "$WT_PREFIX$no", number = 1, title = "${episodes.size} épisodes", poster = cover)),
        )
        val s = WtSerie(tv, episodes)
        wtSeries[no] = System.currentTimeMillis() to s
        s
    }

    private suspend fun wtChargerChapitre(id: String): Chapitre {
        // scan::wt::<titleNo>::<episodeNo>
        val reste = id.removePrefix(WT_PREFIX).split("::")
        val no = reste[0]; val ep = reste.getOrNull(1) ?: "1"
        val (doc, _) = wtDoc("$WT_BASE/fr/x/x/x/viewer?title_no=$no&episode_no=$ep")
        val images = doc.select("#_imageList img").mapNotNull { im ->
            im.attr("data-url").ifBlank { im.attr("src") }.takeIf { it.startsWith("http") }
        }
        fun voisin(sel: String) = doc.selectFirst(sel)?.attr("href")
            ?.let { Regex("episode_no=(\\d+)").find(it)?.groupValues?.get(1) }
            ?.let { "$WT_PREFIX$no::$it" }
        val titre = doc.selectFirst("title")?.text()?.substringBefore(" | WEBTOON")?.trim()
            ?.let { t -> t.substringAfter(" | ", t).let { serie -> "$serie — ${t.substringBefore(" - ")}" } }
            ?: "Épisode $ep"
        Log.i(TAG, "webtoon $no ép. $ep : ${images.size} images")
        return Chapitre(id = id, titre = titre, images = images,
            precedent = voisin("a._prevEpisode"), suivant = voisin("a._nextEpisode"))
    }

    // ───────────────────────── Provider ─────────────────────────

    override suspend fun getHome(): List<Category> = coroutineScope {
        val rangees = listOf(
            "Les plus populaires" to catalogue("popular"),
            "Dernières mises à jour" to catalogue("update"),
            "Nouveautés" to catalogue("latest"),
            "Manga" to catalogue("popular", type = "manga"),
            "Manhwa (Corée)" to catalogue("popular", type = "manhwa"),
            "Manhua (Chine)" to catalogue("popular", type = "manhua"),
        ) + listOf("Action", "Aventure", "Romance", "Fantasy", "Isekai", "Comédie", "Horreur",
            "Thriller", "Science-Fiction", "Surnaturel", "Psychologique", "Sport", "Slice of Life")
            .map { it to catalogue("popular", genre = GENRES[it]) }
        // Anime-Sama : une page du catalogue au hasard + ses grands genres.
        val rangeesAs = listOf("À découvrir sur Anime-Sama" to asCatalogue((1..24).random())) +
            listOf("Shônen", "Isekai", "Romance", "Seinen", "Fantasy", "Action")
                .map { "$it · Anime-Sama" to asCatalogue(genre = it) }

        val resultats = rangees.map { (nom, url) ->
            async { nom to runCatching { liste(url) }.onFailure { Log.w(TAG, "rangée $nom : ${it.message}") }.getOrDefault(emptyList()) }
        }.awaitAll()
        val resultatsAs = rangeesAs.map { (nom, url) ->
            async { nom to runCatching { asListe(url) }.onFailure { Log.w(TAG, "rangée $nom : ${it.message}") }.getOrDefault(emptyList()) }
        }.awaitAll()
        // Webtoon : les séries originales (mélangées) + quelques grands genres.
        val rangeesWt = listOf("Webtoon · séries officielles" to "$WT_BASE/fr/originals") +
            listOf("Romance", "Fantasy", "Action", "Thriller", "Comédie", "Drame")
                .map { "$it · Webtoon" to wtGenre(WT_GENRES.getValue(it)) }
        val resultatsWt = rangeesWt.map { (nom, url) ->
            async { nom to runCatching { wtListe(url).shuffled() }.onFailure { Log.w(TAG, "rangée $nom : ${it.message}") }.getOrDefault(emptyList()) }
        }.awaitAll()

        val out = ArrayList<Category>()
        val populaires = resultats.firstOrNull()?.second.orEmpty()
        val miseAJour = resultats.getOrNull(1)?.second.orEmpty()
        // Carrousel : populaires + séries tout juste mises à jour (12).
        // Copies obligatoires : le carrousel change le type d'affichage de ses éléments ; partager
        //   les mêmes objets avec les rangées faisait planter (ViewPager2 « match_parent »).
        val decouvertesAs = resultatsAs.firstOrNull()?.second.orEmpty()
        val officielsWt = resultatsWt.firstOrNull()?.second.orEmpty()
        val carrousel = (populaires.take(6) + miseAJour.take(3) + decouvertesAs.shuffled().take(3) + officielsWt.take(3))
            .distinctBy { it.id }.map { it.copy() }
        if (carrousel.isNotEmpty()) out.add(Category(Category.FEATURED, carrousel))
        // Sushiscan d'abord (populaires, mises à jour, nouveautés), puis Anime-Sama, puis les genres.
        resultats.take(3).forEach { (nom, l) -> if (l.size >= 4) out.add(Category(nom, l.map { it.copy() })) }
        resultatsAs.forEach { (nom, l) -> if (l.size >= 4) out.add(Category(nom, l.map { it.copy() })) }
        resultatsWt.forEach { (nom, l) -> if (l.size >= 4) out.add(Category(nom, l.map { it.copy() })) }
        resultats.drop(3).forEach { (nom, l) -> if (l.size >= 4) out.add(Category(nom, l.map { it.copy() })) }
        out
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            // Écran de recherche vide → types puis genres, comme le filtre du site.
            return TYPES.map { (nom, t) -> Genre(id = "t:$t", name = nom) } +
                GENRES.map { (nom, id) -> Genre(id = "g:$id", name = nom) } +
                AS_GENRES.map { Genre(id = "as:g:$it", name = "$it · Anime-Sama") } +
                WT_GENRES.map { (nom, g) -> Genre(id = "wt:g:$g", name = "$nom · Webtoon") }
        }
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$BASE/?s=$q" else "$BASE/page/$page/?s=$q"
        return coroutineScope {
            val ss = async { runCatching { liste(url) }.getOrDefault(emptyList()) }
            val asr = async { runCatching { asListe(asCatalogue(page, recherche = query.trim())) }.getOrDefault(emptyList()) }
            val wtr = async {
                if (page > 1) emptyList()
                else runCatching { wtListe("$WT_BASE/fr/search?keyword=${URLEncoder.encode(query.trim(), "UTF-8")}") }.getOrDefault(emptyList())
            }
            // Les trois sites mélangés, un résultat de chaque à tour de rôle.
            val a = ss.await(); val b = asr.await(); val c = wtr.await()
            (0 until maxOf(a.size, b.size, c.size)).flatMap { listOfNotNull(a.getOrNull(it), b.getOrNull(it), c.getOrNull(it)) }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    /** 2026-10-23 (user) : les 3 onglets du profil Scans — « Webtoon » (séries officielles Webtoon),
     *  « Scan 1 » (Sushiscan, ce qu'affichait l'onglet unique) et « Scan 2 » (scans d'Anime-Sama).
     *  L'accueil, lui, reste mélangé. */
    suspend fun getTvShowsOnglet(onglet: String, page: Int): List<TvShow> = runCatching {
        when (onglet) {
            "webtoon" -> if (page == 1) wtListe("$WT_BASE/fr/originals") else emptyList()
            "scan2" -> asListe(asCatalogue(page))
            else -> liste(catalogue("popular", page))
        }
    }.onFailure { Log.w(TAG, "onglet $onglet p$page : ${it.message}") }.getOrDefault(emptyList())

    override suspend fun getTvShows(page: Int): List<TvShow> = runCatching { liste(catalogue("popular", page)) }.getOrDefault(emptyList())

    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "")

    // ── Fiche ──

    private data class Fiche(val tvShow: TvShow, val chapitres: List<Pair<String, String>>)
    private val cacheFiches = ConcurrentHashMap<String, Pair<Long, Fiche>>()

    private fun slugOf(id: String) = id.removePrefix(ID_PREFIX).trim('/')

    private suspend fun fiche(id: String, force: Boolean = false): Fiche {
        val slug = slugOf(id)
        if (!force) cacheFiches[slug]?.let { (t, f) -> if (System.currentTimeMillis() - t < CACHE_MS) return f }
        val doc = getDoc("$BASE/catalogue/$slug/")
        val titre = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val img = doc.selectFirst(".thumb img")
        val cover = (img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))?.substringBefore("?")
        val desc = doc.selectFirst(".entry-content[itemprop=description]")?.text()?.trim()
            ?: doc.selectFirst(".entry-content")?.text()?.trim()
        val infos = doc.select(".tsinfo .imptdt").associate { el ->
            val k = el.ownText().trim().lowercase()
            k to (el.selectFirst("a, i")?.text()?.trim() ?: el.text().removePrefix(el.ownText()).trim())
        }
        fun info(cle: String) = infos.entries.firstOrNull { it.key.startsWith(cle) }?.value?.takeIf { it.isNotBlank() }
        val genres = doc.select(".mgen a").map { a ->
            val nom = a.text().trim()
            Genre(id = GENRES[nom]?.let { "g:$it" } ?: "s:${URLEncoder.encode(nom, "UTF-8")}", name = nom)
        }
        val note = doc.selectFirst(".num[itemprop=ratingValue], .rating .num")?.text()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        // Chapitres : le site les liste du plus récent au plus ancien → on remet dans l'ordre de lecture.
        val chapitres = doc.select("#chapterlist li a, .eplister li a").mapNotNull { a ->
            val href = a.attr("abs:href").takeIf { it.startsWith(BASE) } ?: return@mapNotNull null
            val chSlug = href.removePrefix(BASE).trim('/')
            val nom = a.selectFirst(".chapternum")?.text()?.trim()?.ifBlank { null } ?: a.text().trim()
            chSlug to nom
        }.distinctBy { it.first }.reversed()
        val type = info("type")
        val estVolumes = chapitres.isNotEmpty() && chapitres.all { it.second.contains("Volume", true) }
        val tv = TvShow(
            id = "$ID_PREFIX$slug",
            title = titre,
            overview = listOfNotNull(
                desc,
                listOfNotNull(type, info("statut")?.let { "Statut : $it" }).joinToString(" · ").ifBlank { null },
            ).joinToString("\n\n"),
            released = info("année")?.let { a -> Regex("\\d{4}").find(a)?.value?.let { "$it-01-01" } },
            quality = type,
            rating = note,
            poster = cover,
            banner = cover,
            genres = genres,
            directors = listOfNotNull(info("auteur")?.let { People(id = "${ID_PREFIX}auteur:$it", name = it) }),
            seasons = listOf(
                Season(
                    id = "$ID_PREFIX$slug",
                    number = 1,
                    title = "${chapitres.size} ${if (estVolumes) "volumes" else "chapitres"}",
                    poster = cover,
                )
            ),
        )
        val f = Fiche(tv, chapitres)
        cacheFiches[slug] = System.currentTimeMillis() to f
        return f
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (estWebtoon(id)) {
            val no = id.removePrefix(WT_PREFIX).substringBefore("::")
            return runCatching { wtSerie(no).tv }.getOrElse { Log.w(TAG, "fiche webtoon $id : ${it.message}"); TvShow(id = id) }
        }
        if (estAnimeSama(id)) {
            val slug = id.removePrefix(AS_PREFIX).substringBefore("::")
            return runCatching { asFiche(slug) }.getOrElse { Log.w(TAG, "fiche AS $id : ${it.message}"); TvShow(id = id) }
        }
        val f = runCatching { fiche(id) }.getOrElse { Log.w(TAG, "fiche $id : ${it.message}"); return TvShow(id = id) }
        // « Vous pourriez aussi aimer » : les populaires du premier genre.
        val reco = f.tvShow.genres.firstOrNull()?.id?.removePrefix("g:")?.toIntOrNull()?.let { g ->
            runCatching { liste(catalogue("popular", genre = g)) }.getOrDefault(emptyList()).filter { it.id != f.tvShow.id }.take(16)
        }.orEmpty()
        return f.tvShow.copy(recommendations = reco)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        if (estWebtoon(seasonId)) {
            val no = seasonId.removePrefix(WT_PREFIX).substringBefore("::")
            val s = runCatching { wtSerie(no) }.getOrElse { Log.w(TAG, "épisodes webtoon : ${it.message}"); return emptyList() }
            return s.episodes.mapIndexed { i, (n, nom) ->
                Episode(id = "$WT_PREFIX$no::$n", number = i + 1, title = nom, poster = s.tv.poster)
            }
        }
        if (estAnimeSama(seasonId)) {
            val reste = seasonId.removePrefix(AS_PREFIX).split("::")
            val slug = reste.getOrNull(0) ?: return emptyList()
            val chemin = reste.getOrNull(1) ?: return emptyList()
            val (_, chapitres) = runCatching { asChapitres(slug, chemin) }.getOrElse { Log.w(TAG, "chapitres AS : ${it.message}"); return emptyList() }
            return chapitres.keys.mapIndexed { i, c ->
                Episode(id = "$AS_PREFIX$slug::$chemin::$c", number = i + 1, title = "Chapitre $c")
            }
        }
        val f = runCatching { fiche(seasonId) }.getOrElse { return emptyList() }
        return f.chapitres.mapIndexed { i, (chSlug, nom) ->
            Episode(
                id = "$ID_PREFIX$chSlug",
                number = i + 1,
                title = nom,
                poster = f.tvShow.poster,
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        if (id.startsWith("wt:g:")) {
            val g = id.removePrefix("wt:g:")
            val nom = WT_GENRES.entries.firstOrNull { it.value == g }?.key ?: g
            // Le site rend tout le genre sur une seule page.
            return Genre(id = id, name = "$nom · Webtoon",
                shows = if (page > 1) emptyList() else runCatching { wtListe(wtGenre(g)) }.getOrDefault(emptyList()))
        }
        if (id.startsWith("as:g:")) {
            val g = id.removePrefix("as:g:")
            return Genre(id = id, name = "$g · Anime-Sama", shows = runCatching { asListe(asCatalogue(page, genre = g)) }.getOrDefault(emptyList()))
        }
        val url = when {
            id.startsWith("g:") -> catalogue("popular", page, genre = id.removePrefix("g:").toIntOrNull())
            id.startsWith("t:") -> catalogue("popular", page, type = id.removePrefix("t:"))
            else -> "$BASE/page/$page/?s=${id.removePrefix("s:")}"
        }
        val nom = GENRES.entries.firstOrNull { "g:${it.value}" == id }?.key
            ?: TYPES.entries.firstOrNull { "t:${it.value}" == id }?.key
            ?: java.net.URLDecoder.decode(id.substringAfter(":"), "UTF-8")
        return Genre(id = id, name = nom, shows = runCatching { liste(url) }.getOrDefault(emptyList()))
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id.substringAfterLast(":"))

    // Les scans ne passent pas par le lecteur vidéo (redirection vers ScanReaderActivity).
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        listOf(Video.Server(id = id, name = "Lecture des scans", src = "$BASE/${slugOf(id)}/"))

    override suspend fun getVideo(server: Video.Server): Video = throw Exception("Les scans s'ouvrent dans le lecteur de scans")

    // ───────────────────────── lecteur ─────────────────────────

    data class Chapitre(
        val id: String,
        val titre: String,
        val images: List<String>,
        val precedent: String?,
        val suivant: String?,
    )

    private fun cheminVersId(url: String?): String? {
        if (url.isNullOrBlank() || url == "#") return null
        val chemin = url.substringAfter(BASE, url).trim('/')
        return chemin.takeIf { it.isNotBlank() }?.let { "$ID_PREFIX$it" }
    }

    /** Pages d'un chapitre (ordre de lecture) + chapitres voisins. */
    suspend fun chargerChapitre(id: String): Chapitre = withContext(Dispatchers.IO) {
        if (estAnimeSama(id)) return@withContext asChargerChapitre(id)
        if (estWebtoon(id)) return@withContext wtChargerChapitre(id)
        val slug = slugOf(id)
        val doc = getDoc("$BASE/$slug/")
        val html = doc.outerHtml()
        val debut = html.indexOf("ts_reader.run(")
        var images = emptyList<String>()
        var prev: String? = null
        var next: String? = null
        if (debut >= 0) {
            val fin = html.indexOf(");", debut)
            runCatching {
                val o = JSONObject(html.substring(debut + "ts_reader.run(".length, fin))
                val sources = o.optJSONArray("sources")
                val src = sources?.optJSONObject(0)?.optJSONArray("images")
                if (src != null) images = (0 until src.length()).map { src.getString(it) }.filter { it.startsWith("http") }
                prev = cheminVersId(o.optString("prevUrl"))
                next = cheminVersId(o.optString("nextUrl"))
            }.onFailure { Log.w(TAG, "ts_reader illisible : ${it.message}") }
        }
        if (images.isEmpty()) {
            // Secours : images directement dans #readerarea.
            images = doc.select("#readerarea img").mapNotNull { im ->
                (im.attr("data-src").ifBlank { im.attr("src") }).takeIf { it.startsWith("http") }
            }
        }
        val titre = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: slug
        Log.i(TAG, "chapitre $slug : ${images.size} pages")
        Chapitre(id = "$ID_PREFIX$slug", titre = titre, images = images, precedent = prev, suivant = next)
    }
}
