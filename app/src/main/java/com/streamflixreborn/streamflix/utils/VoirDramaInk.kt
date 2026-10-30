package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/**
 * 2026-10-30 (user : « https://voirdrama.ink/ — on est resté sur un vieux site depuis longtemps »,
 * puis « il faut garder les 2, ils n'ont peut-être pas le même contenu ») — NOUVEAU site VoirDrama,
 * à jour (épisodes du jour), en SOURCE COMPLÉMENTAIRE de l'ancien voirdrama.to (figé depuis juin).
 * Relevé dans le navigateur :
 *   · moteur DataLife Engine, habillage proche de l'ancien (.page-item-detail, .summary_image,
 *     li.wp-manga-chapter) ;
 *   · fiche     /toutes-dramas/go/<id-slug>.html
 *     saison    /toutes-dramas/go/<id-slug>/<n>-saison-<n>.html
 *     épisode   /toutes-dramas/go/<id-slug>/<n>-saison-<n>/<e>-episode.html
 *   · accueil = derniers épisodes ; liste complète /toutes-dramas/page/N/ (10 par page, récents d'abord) ;
 *   · recherche = POST « / » do=search&subaction=search&story=… ;
 *   · épisode : <select id="serial-player-select"> dont les options sont des CLÉS (voe_vf, voe_vostfr,
 *     doodstream_vf…, libellé « VOE — VF ») ; le lecteur est demandé par le script de la page
 *     (/engine/ajax/controller.php, avec un Turnstile invisible) → on fait choisir la clé par la
 *     page elle-même dans une WebView cachée et on capte le flux (cf. WebViewStreamResolver.jsAuChargement).
 */
object VoirDramaInk {
    private const val TAG = "VoirDramaInk"
    const val BASE = "https://voirdrama.ink"
    /** Préfixe des id (fiche, saison, épisode) dans VoirDramaProvider. */
    const val PREFIXE = "vdi::"
    /** Préfixe des id de serveurs (lecture). */
    const val PREFIXE_SERVEUR = "voirdramaink::"

    data class Fiche(val id: String, val titre: String, val affiche: String?)
    data class Ep(val id: String, val numero: Int, val titre: String)
    data class Detail(val titre: String, val affiche: String?, val resume: String?, val saisons: List<Pair<Int, String>>, val genres: List<String>)

    private val client by lazy { NetworkClient.default.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    private fun requete(b: Request.Builder): Document? = runCatching {
        client.newCall(b.header("User-Agent", WebViewResolver.STEALTH_UA).header("Referer", "$BASE/").build()).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code} sur ${r.request.url}"); null }
            else Jsoup.parse(r.body?.string().orEmpty(), r.request.url.toString())
        }
    }.onFailure { Log.w(TAG, "requête KO : ${it.message}") }.getOrNull()

    private suspend fun page(url: String): Document? = withContext(Dispatchers.IO) { requete(Request.Builder().url(url)) }

    private fun relatif(href: String) = href.substringAfter("/toutes-dramas/go/", "").removeSuffix(".html").trimEnd('/')

    private fun fiches(doc: Document): List<Fiche> {
        val vus = HashSet<String>()
        return doc.select(".page-item-detail").mapNotNull { e ->
            val a = e.selectFirst("h3 a, .post-title a") ?: e.select("a[href*=/toutes-dramas/go/]").firstOrNull { it.text().isNotBlank() } ?: return@mapNotNull null
            // Lien vers la fiche (pas vers un épisode) : on garde le 1er segment après /go/.
            val id = relatif(a.attr("abs:href")).substringBefore('/').ifBlank { return@mapNotNull null }
            if (!vus.add(id)) return@mapNotNull null
            val img = e.selectFirst("img")?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }?.ifBlank { null }
            Fiche(id, a.text().trim().ifBlank { id.substringAfter('-').replace('-', ' ') }, img)
        }
    }

    suspend fun accueil(): List<Fiche> = page("$BASE/")?.let { fiches(it) }.orEmpty()
        .also { Log.d(TAG, "accueil : ${it.size} fiche(s)") }

    suspend fun liste(numeroPage: Int): List<Fiche> =
        page(if (numeroPage > 1) "$BASE/toutes-dramas/page/$numeroPage/" else "$BASE/toutes-dramas/")?.let { fiches(it) }.orEmpty()
            .also { Log.d(TAG, "liste p$numeroPage : ${it.size} fiche(s)") }

    suspend fun recherche(q: String): List<Fiche> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        val corps = FormBody.Builder().add("do", "search").add("subaction", "search").add("story", q).build()
        (requete(Request.Builder().url("$BASE/").post(corps))?.let { fiches(it) }.orEmpty())
            .also { Log.d(TAG, "recherche « $q » : ${it.size}") }
    }

    suspend fun detail(id: String): Detail? {
        val doc = page("$BASE/toutes-dramas/go/$id.html") ?: return null
        val titre = doc.selectFirst(".post-title h1, h1")?.text()?.trim().orEmpty()
        val affiche = doc.selectFirst(".summary_image img")?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }?.ifBlank { null }
        val resume = doc.selectFirst(".summary__content, .description-summary, [itemprop=description]")?.text()?.trim()?.ifBlank { null }
        val saisons = doc.select("a[href*=/toutes-dramas/go/$id/]").mapNotNull { a ->
            val r = relatif(a.attr("abs:href"))
            val m = Regex("""^$id/(\d+)-saison-\d+$""").find(r) ?: return@mapNotNull null
            m.groupValues[1].toInt() to r
        }.distinctBy { it.first }.sortedBy { it.first }
        val genres = doc.select(".genres-content a").map { it.text().trim() }
        Log.d(TAG, "fiche $id : « $titre », ${saisons.size} saison(s)")
        return Detail(titre, affiche, resume, saisons, genres)
    }

    /** Épisodes d'une saison (`<id>/<n>-saison-<n>`). */
    suspend fun episodes(saisonId: String): List<Ep> {
        val doc = page("$BASE/toutes-dramas/go/$saisonId.html") ?: return emptyList()
        return doc.select("li.wp-manga-chapter a").mapNotNull { a ->
            val r = relatif(a.attr("abs:href"))
            val n = Regex("""/(\d+)-episode$""").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            Ep(r, n, a.text().trim())
        }.distinctBy { it.numero }.sortedBy { it.numero }
            .also { Log.d(TAG, "saison $saisonId : ${it.size} épisode(s)") }
    }

    /** Lecteurs d'un épisode (`<id>/<n>-saison-<n>/<e>-episode`) : un serveur par clé (VF / VOSTFR). */
    suspend fun serveurs(episodeId: String): List<Video.Server> {
        val url = "$BASE/toutes-dramas/go/$episodeId.html"
        val doc = page(url) ?: return emptyList()
        val liste = doc.select("#serial-player-select option").mapNotNull { o ->
            val cle = o.attr("value").trim()
            val texte = o.text().trim()
            if (!cle.contains('_') || !texte.contains('—')) return@mapNotNull null
            val hebergeur = texte.substringBefore('—').trim()
            val langue = texte.substringAfter('—').trim().uppercase()
            Video.Server(id = "$PREFIXE_SERVEUR$episodeId#$cle", name = "$hebergeur [$langue]", src = "$url#lecteur=$cle")
            // ↑ « #lecteur= » : le dédoublonnage du lecteur coupe l'URL au « # » sauf ce marqueur
            //   (cf. PlayerViewModel, cas FRAnime) → sans lui, les 4 lecteurs (même page
            //   d'épisode) étaient fusionnés en un seul et seul VOE restait.
        }
        Log.d(TAG, "épisode $episodeId : ${liste.size} lecteur(s) ${liste.map { it.name }}")
        return liste
    }

    suspend fun video(server: Video.Server): Video {
        val url = server.src.substringBefore('#')
        val cle = server.src.substringAfter('#', "").removePrefix("lecteur=").replace("'", "")
        val js = """(function(){var s=document.getElementById('serial-player-select');if(!s)return 'pas de liste';
            s.value='$cle';s.dispatchEvent(new Event('change',{bubbles:true}));return 'clé '+s.value;})()"""
        // 1er essai sur l'Oppo : clé choisie (« clé voe_vostfr »), Turnstile passé, mais aucun flux capté
        //   DANS le lecteur en 25 s. On capte donc l'adresse du LECTEUR (cadre /e/<id> de VOE, Dood,
        //   Filemoon ; /embed-<id>.html d'Uqload) et on la confie aux extracteurs existants.
        val r = WebViewStreamResolver.resolve(
            entryUrl = url, referer = "$BASE/", timeoutMs = 20_000L, attach = true,
            jsAuChargement = js, seulementMotifs = true,
            motifsUrlFlux = listOf(
                Regex("""^https?://[^/]+/(e|d|v|embed)/[A-Za-z0-9]{6,}"""),
                Regex("""^https?://[^/]+/embed-[A-Za-z0-9]+\.html"""),
            ),
        ) ?: throw Exception("VoirDrama (nouveau site) : lecteur introuvable ($cle)")
        Log.i(TAG, "lecteur capté ($cle) : ${r.url.substringBefore('?').take(80)} → extracteur")
        val lien = r.url.substringBefore('#')
        // 2026-10-30 : Filemoon passe par des domaines miroirs tournants (kaydendown.lol…) que
        //   l'aiguillage par domaine ne connaît pas → la clé du site dit déjà l'hébergeur.
        if (cle.startsWith("filemoon", ignoreCase = true))
            return com.streamflixreborn.streamflix.extractors.FilemoonExtractor().extract(lien)
        return com.streamflixreborn.streamflix.extractors.Extractor.extract(lien)
    }
}
