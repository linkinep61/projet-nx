package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-11-03 (user : « Donner 2 sources à ajouter au mini drama… », Net Drama retenu) — Net Drama
 * (netdramaizle.com/fr) : mini-dramas verticaux agrégés depuis NetShort, DramaWave, ReelShort,
 * GoodShort et StoryReel. Sous-source de [MiniDramaProvider] (préfixe [PREFIX]), mobile seulement.
 *
 * Relevé en direct dans Chrome puis vérifié hors navigateur (sans cookie, sans compte) :
 *  • Site Astro, pas de Cloudflare : simples pages HTML.
 *  • CARTES : `article.card > a.card-link[href=/fr/serie/<slug>]`, `img` (affiche 2:3),
 *    `h3.card-title`, `p.card-meta` (« 36 épisodes »), badges `.b-sub` VOSTFR / `.b-dub` VF.
 *  • ACCUEIL `/fr/` : rangées `section.row` (titre `h2`, « Tout voir » à retirer).
 *    LISTE VOSTFR `/fr/sous-titres-francais?page=N` (24 par page, ~408 séries), VF
 *    `/fr/doublage-francais`, RECHERCHE `/fr/recherche?q=`.
 *  • FICHE `/fr/serie/<slug>` : JSON-LD `TVSeries` (nom, résumé, affiche, genres) + liens
 *    `/fr/serie/<slug>/episode-<n>`.
 *  • FLUX : la page d'épisode porte `div[data-player]` avec `data-ticket` et `data-sources`
 *    (une entrée par langue, sous-titres INCRUSTÉS dans l'image : `fr-sub` ; VF = `fr`).
 *    Le lecteur du site (vault-player.js) envoie `POST /embed/play` {t:ticket, k:clé, n, f} avec
 *    l'en-tête `x-player: 1` → `{ok:true, kind:"hls", url:"https://v.netdramaizle.com/v/…/index.m3u8"}`.
 *    401 = ticket expiré (on relit la page).
 */
object NetDrama {

    private const val TAG = "NetDrama"
    const val BASE = "https://netdramaizle.com"
    private const val LANG = "fr"
    const val PREFIX = "minidrama-nd-"

    data class Serie(val slug: String, val titre: String, val cover: String?, val nbEps: Int?, val vf: Boolean)
    data class Fiche(val serie: Serie, val resume: String?, val genres: List<String>, val episodes: List<Int>)
    private data class Flux(val url: String, val date: Long)

    private val client get() = NetworkClient.default
    private val ua get() = NetworkClient.USER_AGENT

    val series = ConcurrentHashMap<String, Serie>()
    private val fiches = ConcurrentHashMap<String, Pair<Long, Fiche>>()
    private val flux = ConcurrentHashMap<String, Flux>()
    @Volatile private var rangees: Pair<Long, List<Pair<String, List<String>>>>? = null
    private const val CACHE_MS = 60 * 60 * 1000L
    private const val FLUX_MS = 10 * 60 * 1000L

    private fun lire(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("NetDrama HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    private suspend fun getHtml(url: String): String = withContext(Dispatchers.IO) { lire(url) }

    // ───────────────────────── catalogue ─────────────────────────

    private fun cartes(racine: Element): List<Serie> =
        racine.select("article.card a.card-link[href*=/$LANG/serie/]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/$LANG/serie/").substringBefore('/').substringBefore('?')
            if (slug.isBlank() || a.attr("href").contains("/episode-")) return@mapNotNull null
            val titre = a.selectFirst(".card-title")?.text()?.trim().orEmpty()
                .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            if (titre.isBlank()) return@mapNotNull null
            val cover = a.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("/")) BASE + it else it }
            val nb = a.selectFirst(".card-meta")?.text()?.let { Regex("""(\d+)""").find(it)?.value?.toIntOrNull() }
            val vf = a.selectFirst(".b-dub") != null
            val s = Serie(slug, titre, cover, nb, vf)
            series.merge(slug, s) { ancien, neuf -> if (neuf.cover == null) ancien else neuf }
            s
        }.distinctBy { it.slug }

    /** Liste VOSTFR complète, 24 séries par page. */
    suspend fun fetchCatalog(page: Int = 1): List<Serie> {
        val url = if (page <= 1) "$BASE/$LANG/sous-titres-francais" else "$BASE/$LANG/sous-titres-francais?page=$page"
        return cartes(Jsoup.parse(getHtml(url)))
    }

    suspend fun search(q: String): List<Serie> =
        cartes(Jsoup.parse(getHtml("$BASE/$LANG/recherche?q=" + URLEncoder.encode(q, "UTF-8"))))

    /** Rangées de l'accueil du site, dans son ordre (une seule page lue, cache 1 h). */
    suspend fun rangeesAccueil(): List<Pair<String, List<String>>> {
        rangees?.let { (t, l) -> if (System.currentTimeMillis() - t < CACHE_MS && l.isNotEmpty()) return l }
        val doc = Jsoup.parse(getHtml("$BASE/$LANG/"))
        val out = ArrayList<Pair<String, List<String>>>()
        for (sec in doc.select("section.row")) {
            val titre = sec.selectFirst("h2")?.text()?.replace("Tout voir", "")?.trim().orEmpty()
            if (titre.isBlank() || titre.startsWith("Reprendre")) continue
            val l = cartes(sec)
            if (l.size >= 3) out.add(titre to l.map { it.slug })
        }
        if (out.isNotEmpty()) rangees = System.currentTimeMillis() to out
        Log.i(TAG, "accueil : ${out.size} rangées, ${series.size} séries")
        return out
    }

    // ───────────────────────── épisodes ─────────────────────────

    suspend fun fetchEpisodes(slug: String): Fiche {
        fiches[slug]?.let { (t, f) -> if (System.currentTimeMillis() - t < CACHE_MS) return f }
        val html = getHtml("$BASE/$LANG/serie/$slug")
        val doc = Jsoup.parse(html)
        var titre: String? = null; var resume: String? = null; var image: String? = null
        val genres = ArrayList<String>()
        for (s in doc.select("script[type=application/ld+json]")) {
            runCatching {
                val o = JSONObject(s.data())
                if (o.optString("@type") != "TVSeries") return@runCatching
                titre = o.optString("name").ifBlank { null }
                resume = o.optString("description").ifBlank { null }
                image = o.optString("image").ifBlank { null }
                o.optJSONArray("genre")?.let { g -> for (i in 0 until g.length()) g.optString(i).takeIf { it.isNotBlank() }?.let { genres.add(it) } }
            }
        }
        val re = Regex("""/$LANG/serie/${Regex.escape(slug)}/episode-(\d+)""")
        val eps = re.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.toSortedSet().toList()
        val connue = series[slug]
        val serie = Serie(
            slug,
            titre ?: connue?.titre ?: doc.selectFirst("h1")?.text().orEmpty(),
            connue?.cover ?: image,
            eps.size.takeIf { it > 0 } ?: connue?.nbEps,
            connue?.vf ?: false,
        )
        series[slug] = serie
        val f = Fiche(serie, resume, genres, eps)
        if (eps.isNotEmpty()) fiches[slug] = System.currentTimeMillis() to f
        Log.i(TAG, "série $slug : ${eps.size} épisodes")
        return f
    }

    fun episodeUrl(slug: String, n: Int) = "$BASE/$LANG/serie/$slug/episode-$n"

    // ───────────────────────── flux ─────────────────────────

    private val JSON = "application/json".toMediaType()

    /** Adresse HLS d'un épisode, sous-titres français incrustés (ou VF si la série est doublée). */
    suspend fun fetchStream(slug: String, n: Int, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val k = "$slug|$n"
        if (!force) flux[k]?.let { if (System.currentTimeMillis() - it.date < FLUX_MS) return@withContext it.url }
        val doc = Jsoup.parse(lire(episodeUrl(slug, n)))
        val p = doc.selectFirst("[data-player]") ?: throw IOException("NetDrama : pas de lecteur sur l'épisode $n")
        val ticket = p.attr("data-ticket").ifBlank { throw IOException("NetDrama : ticket absent") }
        // (Jsoup a déjà décodé les entités HTML de l'attribut.)
        val sources = runCatching { JSONArray(p.attr("data-sources")) }.getOrNull() ?: JSONArray()
        val cles = (0 until sources.length()).mapNotNull { sources.optJSONObject(it)?.optString("key") }
        // Préférence : VF doublée (`fr`), sinon sous-titres français incrustés (`fr-sub`).
        val cle = listOf("fr", "fr-sub").firstOrNull { it in cles } ?: p.attr("data-current").ifBlank { "fr-sub" }
        val corps = JSONObject().put("t", ticket).put("k", cle).put("n", 0).put("f", 0).toString()
        val req = Request.Builder().url("$BASE/embed/play")
            .header("User-Agent", ua)
            .header("x-player", "1")
            .header("Origin", BASE)
            .header("Referer", episodeUrl(slug, n))
            // 2026-11-03 (403 « Forbidden » dans l'appli, 200 depuis le PC) : NetworkClient ajoute à TOUTE
            //   requête des en-têtes de navigation (Sec-Fetch-Site: none…). Prouvé en les rejouant un par
            //   un depuis le PC : c'est `Sec-Fetch-Site: none` qui fait refuser /embed/play. On envoie donc
            //   ceux du fetch() du lecteur du site.
            .header("Accept", "*/*")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .post(corps.toRequestBody(JSON))
            .build()
        var cookie: String? = null
        val rep = client.newCall(req).execute().use { r ->
            // 2026-11-03 : l'adresse « /embed/m/… » n'est servie qu'avec le cookie `vk` posé ici
            //   (Path=/embed, HttpOnly). Sans lui : 403 « Forbidden ». Si le client a déjà ce cookie
            //   (pas de Set-Cookie), on le reprend dans son pot à cookies.
            cookie = r.headers("Set-Cookie").map { it.substringBefore(';') }.filter { it.startsWith("vk=") }
                .ifEmpty { client.cookieJar.loadForRequest(r.request.url).filter { it.name == "vk" }.map { "vk=${it.value}" } }
                .joinToString("; ").ifBlank { null }
            val corpsRep = r.body?.string().orEmpty()
            // Diagnostic en cas de refus (le site a déjà changé une fois ses conditions).
            if (r.code != 200) Log.w(TAG, "embed/play $k ($cle) → HTTP ${r.code}, réponse « ${corpsRep.take(160)} »")
            if (r.code == 401) throw IOException("NetDrama : ticket expiré")
            corpsRep
        }
        val o = runCatching { JSONObject(rep) }.getOrNull()
        // 2026-11-03 : l'adresse peut être relative (« /embed/m/… ») ou complète (« https://v.netdramaizle.com/… »).
        val url = o?.takeIf { it.optBoolean("ok") }?.optString("url")?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("/")) BASE + it else it }?.takeIf { it.startsWith("http") }
            ?: throw IOException("NetDrama : flux indisponible (${rep.take(120)})")
        // 2026-11-03 : séries sans version française incrustée (clé unique « zh ») → `kind:"mp4"` (fichier
        //   MP4, pas du HLS) et sous-titres FR à part : `<track kind="subtitles" srclang="fr" src="/embed/vtt/…">`.
        val kind = o.optString("kind")
        val stFr = if (cle.startsWith("fr")) null
            else doc.selectFirst("track[kind=subtitles][srclang=fr]")?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("/")) BASE + it else it }
        Log.i(TAG, "$k : $cle $kind ${if (url.contains("/embed/m/")) "(embed/m, cookie ${if (cookie != null) "ok" else "ABSENT"})" else ""}${if (stFr != null) " + sous-titres FR à part" else ""}")
        cookie?.let { cookies[url] = it }
        kinds[url] = kind
        stFr?.let { st -> telechargerVtt(st, episodeUrl(slug, n), "$slug-$n")?.let { sousTitres[url] = it } }
        flux[k] = Flux(url, System.currentTimeMillis())
        url
    }

    /**
     * 2026-11-03 : « /embed/vtt/… » refuse (403) les en-têtes de navigation que NetworkClient ajoute,
     * et le lecteur ne transmet pas nos en-têtes aux sous-titres. On le télécharge donc ici avec les
     * en-têtes d'un fetch() et on donne au lecteur un fichier local (file://).
     */
    private fun telechargerVtt(src: String, referer: String, nom: String): String? = runCatching {
        val req = Request.Builder().url(src)
            .header("User-Agent", ua).header("Referer", referer)
            .header("Accept", "*/*").header("Sec-Fetch-Dest", "track")
            .header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Site", "same-origin")
            .build()
        val texte = client.newCall(req).execute().use { r ->
            if (r.code != 200) { Log.w(TAG, "sous-titres $nom → HTTP ${r.code}"); return@runCatching null }
            r.body?.string().orEmpty()
        }
        if (!texte.trimStart('﻿').startsWith("WEBVTT")) { Log.w(TAG, "sous-titres $nom : pas du WebVTT"); return@runCatching null }
        val dossier = java.io.File(com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext.cacheDir, "st_netdrama").apply { mkdirs() }
        val f = java.io.File(dossier, nom.replace(Regex("[^a-z0-9-]"), "_") + ".vtt")
        f.writeText(texte)
        android.net.Uri.fromFile(f).toString()
    }.getOrNull()

    private val cookies = ConcurrentHashMap<String, String>()
    private val kinds = ConcurrentHashMap<String, String>()
    private val sousTitres = ConcurrentHashMap<String, String>()

    fun video(url: String): Video {
        // En-têtes d'un fetch() du lecteur du site, pour TOUT (flux, segments, sous-titres) : avec les
        //   en-têtes de navigation ajoutés par NetworkClient, « /embed/m/… » et « /embed/vtt/… » répondent
        //   403 (prouvé depuis le PC) ; les CDN (v.netdramaizle.com, awscdn.netshort.com) acceptent les deux.
        val h = mutableMapOf(
            "User-Agent" to ua, "Referer" to "$BASE/",
            "Accept" to "*/*", "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-origin",
        )
        // « /embed/m/… » : cookie `vk` posé par /embed/play, sinon 403 « Forbidden ».
        if (url.startsWith("$BASE/embed/")) cookies[url]?.let { h["Cookie"] = it }
        val mp4 = kinds[url] == "mp4"
        return Video(
            source = url,
            subtitles = sousTitres[url]?.let { listOf(Video.Subtitle("Français", it, initialDefault = true)) } ?: listOf(),
            headers = h,
            type = if (mp4) "video/mp4" else "application/vnd.apple.mpegurl",
        )
    }

    // Épisodes d'environ 1 min 30 : on prépare le suivant pendant qu'on regarde celui-ci.
    private val prechauffage = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun prechauffer(slug: String, n: Int) {
        flux["$slug|$n"]?.let { if (System.currentTimeMillis() - it.date < FLUX_MS) return }
        prechauffage.launch { runCatching { fetchStream(slug, n) } }
    }
}
