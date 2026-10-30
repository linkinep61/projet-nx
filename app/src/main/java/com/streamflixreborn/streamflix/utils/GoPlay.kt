package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * 2026-10-30 (user : « ce site-là pourrait bien faire un serveur supplémentaire chez nous » ;
 * « rajoute-le en tant que serveur supplémentaire ») : GoPlay (goplay.su) — dramas coréens/
 * chinois/thaïs + variétés, repris des plateformes (Youku, iQIYI, WeTV…), avec des
 * SOUS-TITRES FRANÇAIS d'origine et du 1080p (DASH, sans DRM). Relevé dans le navigateur :
 *   · Recherche  `/?search=<titre>` → liens `/<idHex>/<Slug>/` (HTML servi par le serveur).
 *   · Fiche      `/<idHex>/<Slug>/` (= épisode 1) → liens `/<idHexEp>/<Slug>/Episode-<n>/`,
 *                présents EN DOUBLE dans la page (dédoublonnés par numéro).
 *   · Épisode    lecteur JW Player : l'URL du flux (`/stream/stream_dash_v3.mpd?d=<jeton>`) et
 *                des sous-titres (`/service/subs.vtt?d=<jeton>`, « French ») sont fabriquées par
 *                un script obfusqué → on ouvre la page dans une WebView invisible qui capte le
 *                .mpd, puis on lit `jwplayer().getPlaylistItem()` pour les sous-titres.
 *   · Accueil    sections `<turbo-frame id="toptitles">` (« Séries les plus regardées »)…
 */
object GoPlay {

    private const val TAG = "GoPlay"
    const val BASE = "https://goplay.su"
    const val PREFIXE = "goplay::"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /** Lien de fiche : `/<idHex>/<Slug>/` (href parfois sans « / » initial). */
    private val FICHE = Regex("""^/?([0-9a-fA-F]{6,})/([^/?#]+)/?$""")
    /** Lien d'épisode : `/<idHexEp>/<Slug>/Episode-<n>/`. */
    private val EPISODE = Regex("""^/?([0-9a-fA-F]{6,})/([^/?#]+)/Episode-(\d+)/?$""", RegexOption.IGNORE_CASE)

    data class Fiche(val chemin: String, val titre: String, val affiche: String?, val resume: String? = null)
    data class Ep(val numero: Int, val chemin: String, val vignette: String?)

    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(10, TimeUnit.SECONDS)
            // les cookies viennent de la WebView (CookieManager) : pas de bocal OkHttp qui les écraserait
            .cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    }

    // 2026-10-30 (Oppo : « HTTP 404 sur https://goplay.su/ ») — vérifié dans le navigateur :
    //   même page = 200 AVEC les cookies du site, 404 SANS. Les cookies sont posés par la page
    //   elle-même (vérification Cloudflare automatique) → on ouvre une fois l'accueil dans une
    //   WebView cachée (WebViewResolver), puis on rejoue les requêtes avec ses cookies et SON
    //   User-Agent (le cookie Cloudflare est lié à l'UA).
    private val amorcage = kotlinx.coroutines.sync.Mutex()

    private fun cookies(): String? = runCatching {
        android.webkit.CookieManager.getInstance().getCookie(BASE)
    }.getOrNull()?.ifBlank { null }

    private fun requete(url: String): Pair<Int, String?> = runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", WebViewResolver.STEALTH_UA)
                .header("Referer", "$BASE/")
                .apply { cookies()?.let { header("Cookie", it) } }
                .build()
        ).execute().use { r -> r.code to (if (r.isSuccessful) r.body?.string() else null) }
    }.onFailure { Log.w(TAG, "requête KO $url : ${it.message}") }.getOrDefault(0 to null)

    private suspend fun amorcer(cookiesVus: String?) = amorcage.withLock {
        // Un autre appel vient peut-être de faire l'amorçage pendant qu'on attendait le verrou.
        if (cookies() != null && cookies() != cookiesVus) { Log.d(TAG, "amorçage déjà fait par un autre appel"); return@withLock }
        val avant = cookies()
        Log.d(TAG, "amorçage WebView (cookies avant : ${avant?.split(';')?.map { it.substringBefore('=').trim() }})")
        val ctx = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity
            ?: com.streamflixreborn.streamflix.StreamFlixApp.instance
        val resolver = WebViewResolver(ctx)
        try {
            runCatching { resolver.get("$BASE/", silent = true, contentMarker = "indexepisodes") }
                .onFailure { Log.w(TAG, "amorçage KO : ${it.message}") }
        } finally { runCatching { resolver.cleanup() } }
        Log.d(TAG, "amorçage fini (cookies après : ${cookies()?.split(';')?.map { it.substringBefore('=').trim() }})")
    }

    suspend fun page(url: String): Document? = withContext(Dispatchers.IO) {
        val cookiesVus = cookies()
        var (code, html) = requete(url)
        if (html == null && (code == 403 || code == 404 || code == 503)) {
            Log.d(TAG, "HTTP $code sur $url → amorçage des cookies")
            amorcer(cookiesVus)
            requete(url).let { code = it.first; html = it.second }
        }
        if (html == null) { Log.w(TAG, "HTTP $code sur $url"); null }
        else Jsoup.parse(html!!, url)
    }

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("""\(\d{4}\)"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun absolu(href: String) = if (href.startsWith("http")) href else "$BASE/" + href.trimStart('/')

    /** Titre lisible tiré du slug (« Where-the-Mask-Ends » → « Where the Mask Ends »). */
    fun titreDuSlug(slug: String) = slug.replace('-', ' ').replace(Regex("\\s+"), " ").trim()

    /** 2026-10-30 : plusieurs sections de l'accueil avec UNE seule requête
     *  (« toptitles » = Séries les plus regardées, « freshkdrama » = Nouveaux K-Dramas). */
    suspend fun accueilSections(ids: List<String>, max: Int = 20): Map<String, List<Fiche>> {
        val doc = page("$BASE/") ?: return emptyMap()
        return ids.associateWith { sectionAccueil(doc, it, max) }
    }

    /** Cartes `#indexepisodes` d'une section d'accueil (`turbo-frame#<idSection>`). */
    suspend fun accueil(idSection: String, max: Int = 20): List<Fiche> {
        val doc = page("$BASE/") ?: return emptyList()
        return sectionAccueil(doc, idSection, max)
    }

    private fun sectionAccueil(doc: Document, idSection: String, max: Int): List<Fiche> {
        val cadre = doc.selectFirst("turbo-frame#$idSection") ?: run {
            Log.w(TAG, "accueil : section « $idSection » absente"); return emptyList()
        }
        val vus = HashSet<String>()
        val fiches = cadre.select("div#indexepisodes").mapNotNull { carte ->
            val a = carte.selectFirst("a[href]") ?: return@mapNotNull null
            val m = FICHE.find(a.attr("href")) ?: return@mapNotNull null
            val chemin = "${m.groupValues[1]}/${m.groupValues[2]}"
            if (!vus.add(chemin)) return@mapNotNull null
            val titre = carte.selectFirst("#indexepisodetitle")?.text()?.trim()?.ifBlank { null }
                ?: titreDuSlug(m.groupValues[2])
            Fiche(chemin, titre, carte.selectFirst("img")?.attr("src")?.ifBlank { null })
        }.take(max)
        Log.d(TAG, "accueil « $idSection » : ${fiches.size} fiche(s)")
        return fiches
    }

    // 2026-10-30 (user « garder séries et films et tout mettre dans un même paquet ») :
    //   bibliothèque du site, relevée dans le navigateur : /browse?type=1 = « Séries »,
    //   type=2 = « Films » (titre de page « Parcourir la bibliothèque (Films) »), &page=N,
    //   35 à 42 fiches par page. Fiche = div.browse_entry : image .browse_entry_image,
    //   titre .browse_entry_title, résumé .browse_entry_description, lien /<hex>/<Slug>/.
    //   Un film a UN épisode (/Episode-0/).
    const val TYPE_SERIES = 1
    const val TYPE_FILMS = 2

    suspend fun bibliotheque(type: Int, numeroPage: Int): List<Fiche> {
        val doc = page("$BASE/browse?type=$type&page=${numeroPage.coerceAtLeast(1)}") ?: return emptyList()
        val vus = HashSet<String>()
        val fiches = doc.select("div.browse_entry").mapNotNull { e ->
            val m = e.select("a[href]").firstNotNullOfOrNull { FICHE.find(it.attr("href")) } ?: return@mapNotNull null
            val chemin = "${m.groupValues[1]}/${m.groupValues[2]}"
            if (!vus.add(chemin)) return@mapNotNull null
            Fiche(
                chemin,
                e.selectFirst(".browse_entry_title")?.text()?.trim()?.ifBlank { null } ?: titreDuSlug(m.groupValues[2]),
                e.selectFirst("img")?.attr("src")?.ifBlank { null },
                e.selectFirst(".browse_entry_description")?.text()?.trim()?.ifBlank { null },
            )
        }
        Log.d(TAG, "bibliothèque type=$type page=$numeroPage : ${fiches.size} fiche(s)")
        return fiches
    }

    /** Recherche du site → fiches (a > #searchepisodeimage img + #indexsearchepisodetitle). */
    suspend fun rechercheFiches(titre: String): List<Fiche> {
        if (titre.isBlank()) return emptyList()
        val doc = page("$BASE/?search=" + java.net.URLEncoder.encode(titre, "UTF-8")) ?: return emptyList()
        val vus = HashSet<String>()
        val fiches = doc.select("a[href]").mapNotNull { a ->
            val m = FICHE.find(a.attr("href")) ?: return@mapNotNull null
            val chemin = "${m.groupValues[1]}/${m.groupValues[2]}"
            if (!vus.add(chemin)) return@mapNotNull null
            Fiche(
                chemin,
                a.selectFirst("#indexsearchepisodetitle")?.text()?.trim()?.ifBlank { null } ?: titreDuSlug(m.groupValues[2]),
                a.selectFirst("img")?.attr("src")?.ifBlank { null },
            )
        }
        Log.d(TAG, "recherche fiches « $titre » : ${fiches.size}")
        return fiches
    }

    /** Épisodes d'une fiche (`<idHex>/<Slug>`), dédoublonnés et triés. */
    suspend fun episodes(chemin: String): List<Ep> {
        val doc = page("$BASE/$chemin/") ?: return emptyList()
        return doc.select("a[href]").mapNotNull { a ->
            val m = EPISODE.find(a.attr("href")) ?: return@mapNotNull null
            Ep(m.groupValues[3].toInt(), "${m.groupValues[1]}/${m.groupValues[2]}/Episode-${m.groupValues[3]}",
                a.selectFirst("img")?.attr("src")?.ifBlank { null })
        }.distinctBy { it.numero }.sortedBy { it.numero }
    }

    /** Fiches dont le titre (ou le slug) correspond exactement à l'un des titres voulus. */
    private suspend fun rechercher(titre: String, voulus: Set<String>): List<String> {
        val doc = page("$BASE/?search=" + java.net.URLEncoder.encode(titre, "UTF-8")) ?: return emptyList()
        val tous = doc.select("a[href]").mapNotNull { a ->
            val m = FICHE.find(a.attr("href")) ?: return@mapNotNull null
            val texte = a.text().trim().ifBlank { a.parent()?.text()?.trim().orEmpty() }
            Triple("${m.groupValues[1]}/${m.groupValues[2]}", norm(titreDuSlug(m.groupValues[2])), norm(texte))
        }.distinctBy { it.first }
        val bons = tous.filter { (_, slug, texte) -> slug in voulus || texte in voulus }.map { it.first }
        Log.d(TAG, "recherche « $titre » : ${tous.size} résultat(s), ${bons.size} retenu(s) ${bons.take(3)}")
        return bons
    }

    /** Serveur GoPlay pour un titre (titres connus TMDB/site), ou liste vide. */
    suspend fun serveurs(titres: Collection<String>, estUnFilm: Boolean, saison: Int, episode: Int): List<Video.Server> =
        withContext(Dispatchers.IO) {
            val s = saison.coerceAtLeast(1)
            val propres = titres.filter { it.isNotBlank() }.distinct()
            val voulus = propres.flatMap { t ->
                if (estUnFilm || s == 1) listOf(norm(t)) else listOf(norm("$t Season $s"), norm("$t $s"))
            }.toSet()
            if (voulus.isEmpty()) return@withContext emptyList()
            val voulu = if (estUnFilm) 1 else episode
            for (t in propres.take(3)) {
                // Le même titre peut exister sur plusieurs plateformes : on prend la 1re fiche qui a l'épisode.
                for (fiche in rechercher(t, voulus).take(3)) {
                    val eps = episodes(fiche)
                    val ep = eps.firstOrNull { it.numero == voulu } ?: if (estUnFilm) eps.firstOrNull() else null
                    if (ep == null) { Log.d(TAG, "« $fiche » : ${eps.size} épisode(s), pas le n° $voulu"); continue }
                    Log.i(TAG, "« $fiche » → épisode $voulu = ${ep.chemin}")
                    return@withContext listOf(serveurEpisode(ep.chemin))
                }
            }
            emptyList()
        }

    /** Serveur d'un épisode déjà connu (`<idHexEp>/<Slug>/Episode-<n>`). */
    fun serveurEpisode(cheminEp: String) = Video.Server(
        id = "$PREFIXE$cheminEp",
        name = "GoPlay [VOSTFR]",
        src = "$BASE/${cheminEp.trimStart('/')}/",
    )

    // 2026-10-30 (Oppo : vidéo noire, pas de son, « sous-titres aucun ») — relevé dans le navigateur :
    //   · les adresses du flux (`/stream/…m3u8?d=…`) et des sous-titres (`/service/subs.vtt?d=…`)
    //     ne servent qu'UNE fois (2e appel = 403) ;
    //   · sur le serveur Youku le HLS se déclare chiffré mais ne l'est pas (cf. GoPlayHls).
    //   Passe 1 : on capte le flux AVANT que la page ne s'en serve (il reste neuf pour notre lecteur).
    //   Passe 2 (en parallèle) : la page joue normalement et on capte cette fois la requête des
    //   sous-titres, qu'on télécharge nous-mêmes une seule fois dans un fichier local.
    private val SOUS_TITRES = Regex("""/service/subs\.vtt""")
    private val MOTS_FR = setOf("le", "la", "les", "de", "des", "et", "est", "je", "tu", "vous", "pas", "que", "une", "un", "il", "elle", "ne", "ce", "pour", "dans", "mon", "ma", "c'est")
    private val MOTS_EN = setOf("the", "and", "is", "you", "i", "not", "that", "a", "it", "he", "she", "to", "of", "my", "your", "what", "this", "for", "in", "are", "don't")

    private suspend fun sousTitresFrancais(src: String): Video.Subtitle? {
        val r = WebViewStreamResolver.resolve(
            entryUrl = src, referer = "$BASE/", timeoutMs = 20_000L, attach = true, clickPlay = true,
            motifsUrlFlux = listOf(SOUS_TITRES), seulementMotifs = true,
        ) ?: run { Log.d(TAG, "sous-titres : aucune requête captée"); return null }
        return withContext(Dispatchers.IO) {
            runCatching {
                val texte = client.newCall(
                    Request.Builder().url(r.url).apply {
                        r.headers.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true)) header(k, v) }
                        cookies()?.let { header("Cookie", it) }
                    }.build()
                ).execute().use { rep -> if (rep.isSuccessful) rep.body?.string() else { Log.w(TAG, "sous-titres HTTP ${rep.code}"); null } }
                    ?: return@runCatching null
                if (!texte.contains("-->")) { Log.w(TAG, "sous-titres : contenu inattendu (${texte.length} car.)"); return@runCatching null }
                val dossier = java.io.File(com.streamflixreborn.streamflix.StreamFlixApp.instance.cacheDir, "st_goplay").apply { mkdirs() }
                dossier.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                val f = java.io.File(dossier, "${src.hashCode().toUInt()}.vtt").apply { writeText(texte) }
                // 2026-10-30 (Oppo) : la piste annoncée « French » était en réalité en ANGLAIS
                //   (« The only Wei's Purple Peony in the garden. ») → on regarde la langue réelle,
                //   pour que la traduction automatique se déclenche quand c'est de l'anglais.
                val mots = texte.lowercase().split(Regex("""[^\p{L}']+"""))
                val fr = mots.count { it in MOTS_FR }; val en = mots.count { it in MOTS_EN }
                val label = if (en > fr) "English" else "Français"
                Log.d(TAG, "sous-titres : ${texte.lines().count { it.contains("-->") }} réplique(s), langue=$label (fr=$fr en=$en) → ${f.name}")
                Video.Subtitle(label = label, file = "file://" + f.absolutePath, default = true, initialDefault = true)
            }.onFailure { Log.w(TAG, "sous-titres KO : ${it.message}") }.getOrNull()
        }
    }

    suspend fun video(server: Video.Server): Video = kotlinx.coroutines.coroutineScope {
        val st = async { runCatching { sousTitresFrancais(server.src) }.getOrNull() }
        val r = WebViewStreamResolver.resolve(
            entryUrl = server.src, referer = "$BASE/", timeoutMs = 30_000L, attach = true, clickPlay = true,
        ) ?: throw Exception("GoPlay : aucun flux capté (${server.src})")
        val flux = r.url
        val hls = flux.substringBefore('?').endsWith(".m3u8")
        // 2026-10-30 (Oppo : une fois, aucune requête de sous-titres en 20 s → la vidéo attendait
        //   20 s pour rien) : une fois le flux capté, on n'attend plus les sous-titres que 8 s.
        val sousTitres = listOfNotNull(kotlinx.coroutines.withTimeoutOrNull(8_000L) { st.await() }
            ?: run { Log.d(TAG, "sous-titres pas prêts 8 s après le flux → lecture sans eux"); st.cancel(); null })
        // Le flux est sur goplay.su (protégé par Cloudflare) : on reprend les cookies de la WebView.
        val ck = cookies()
        GoPlayHls.uaFlux = r.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        Log.d(TAG, "UA du flux (playlist + segments externes) : ${GoPlayHls.uaFlux}")
        Log.i(TAG, "flux capté (${if (hls) "hls" else if (flux.contains(".mpd")) "dash" else "autre"}), " +
            "sous-titres ${sousTitres.joinToString { it.label }.ifBlank { "aucun" }}, cookies=${ck != null}")
        Video(
            source = flux,
            subtitles = sousTitres,
            type = if (hls) MimeTypes.APPLICATION_M3U8 else if (flux.contains(".mpd")) MimeTypes.APPLICATION_MPD else null,
            headers = r.headers + mapOf("Referer" to "$BASE/", "Origin" to BASE) + (ck?.let { mapOf("Cookie" to it) } ?: emptyMap()),
            // HLS GoPlay : init à nettoyer + playlists à usage unique (cf. GoPlayHls).
            drmType = if (hls) "goplay-hls" else null,
        )
    }
}
