package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver
import com.streamflixreborn.streamflix.utils.WebViewStreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 2026-10-23 — DramaExpress (dramaexpress.net/fr) : mini-dramas verticaux en français, agrégés
 * depuis ~25 applis (GoodShort, ShortTV, DramaBox, NetShort, MyDramaWave…). Sous-source de
 * [MiniDramaProvider] (préfixe [PREFIX]), mobile seulement comme lui.
 *
 * Relevé en direct dans Chrome :
 *  • Site Next.js derrière un challenge Cloudflare « managed » : une simple requête HTTP reçoit
 *    403 + `cf-mitigated: challenge` (curl_cffi chrome124/131/136, safari, firefox, edge : tous
 *    403). Il faut un vrai moteur JavaScript → le WebView de l'appli ([WebViewResolver]) passe le
 *    challenge, pose `cf_clearance` dans le CookieManager, puis OkHttp relit les pages avec CE
 *    cookie et LA MÊME UA (le cookie est lié à l'UA). Session réutilisée tant qu'elle marche ;
 *    sur 403/503 → nouvelle session (nouveau passage du challenge) et UNE seule relance.
 *  • CATALOGUE : cartes `a[href^="/fr/series/"][aria-label]` + `img[src]` (URL CDN d'origine,
 *    gardée telle quelle) — accueil `/fr` (sections « À la une », « Populaires »…), liste
 *    `/fr/series/page/<n>` (29 cartes par page), recherche `/fr/search?q=`.
 *  • ÉPISODES : liens `/fr/series/<slug>/episode-<n>` de la page série ; titre / résumé / affiche
 *    dans le JSON-LD `TVSeries`.
 *  • FLUX : la page d'un épisode porte, dans les données Next.js (`self.__next_f`), un objet
 *    `"sourceFirst":{"source":"ShortTV","chain":[{"enc":"…","type":"hls"|"mp4"|"goodshort"|…}]}`.
 *    `enc` = base64(IV 12 octets + texte chiffré AES-GCM), clé fixe embarquée dans le lecteur
 *    du site → on obtient l'URL d'origine (m3u8 ou mp4). Certaines (ShortTV) refusent une lecture
 *    directe (403) : le site passe alors par son relais `cdn.dramadizilerim.xyz/https/<hôte>/…`
 *    qui exige Referer/Origin dramaexpress.net (451 sinon) — testé, segments compris.
 */
object DramaExpress {

    private const val TAG = "DramaExpress"
    const val BASE = "https://dramaexpress.net"
    private const val LANG = "fr"
    const val PREFIX = "minidrama-de-"

    private const val RELAIS = "https://cdn.dramadizilerim.xyz/"
    private const val CLE_B64 = "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc="

    data class Serie(val slug: String, val titre: String, val cover: String?)
    data class Fiche(val serie: Serie, val resume: String?, val episodes: List<Int>)
    data class Lien(val url: String, val type: String, val source: String)
    data class Flux(val liens: List<Lien>, val sousTitre: String?, val date: Long)

    private val client get() = NetworkClient.default
    private val ua get() = WebViewResolver.STEALTH_UA

    val series = ConcurrentHashMap<String, Serie>()
    private val fiches = ConcurrentHashMap<String, Pair<Long, Fiche>>()
    private val flux = ConcurrentHashMap<String, Flux>()
    @Volatile private var rangees: Pair<Long, List<Pair<String, List<String>>>>? = null
    private const val CACHE_MS = 60 * 60 * 1000L
    // Les liens mp4 DramaBox sont signés et « refresh » : on relit la page passé 10 min.
    private const val FLUX_MS = 10 * 60 * 1000L

    // ───────────────────────── session Cloudflare ─────────────────────────

    private val sessionMutex = Mutex()
    private var resolver: WebViewResolver? = null

    private fun cookies(): String = runCatching {
        android.webkit.CookieManager.getInstance().getCookie(BASE).orEmpty()
    }.getOrDefault("")

    private fun estChallenge(html: String): Boolean {
        val debut = html.take(4000)
        return debut.contains("<title>Just a moment", true) || debut.contains("challenge-platform") ||
            debut.contains("cf-chl", true) || debut.contains("Checking your browser", true)
    }

    private fun lire(url: String): Pair<Int, String> {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Referer", "$BASE/$LANG")
            .apply { cookies().takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .build()
        client.newCall(req).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    // Le passage du challenge tourne À PART de l'appelant : si l'écran qui l'a demandé abandonne
    //   (ex. « Continuer à regarder » coupe ses appels au bout de quelques secondes), le WebView
    //   finit quand même son travail et le cookie servira à l'appel suivant. Un seul à la fois.
    @Volatile private var renouvellement: kotlinx.coroutines.Deferred<Unit>? = null

    private suspend fun renouvelerSession() {
        val d = synchronized(this) {
            renouvellement?.takeIf { it.isActive } ?: prechauffage.async {
                val rv = resolver ?: WebViewResolver(StreamFlixApp.instance).also { resolver = it }
                runCatching { rv.get("$BASE/$LANG", silent = true, clearanceOnly = true) }
                Unit
            }.also { renouvellement = it }
        }
        d.await()
    }

    private fun ok(r: Pair<Int, String>) = r.first == 200 && !estChallenge(r.second)

    /** Page HTML du site : session existante → sinon nouvelle session (challenge) + 1 relance. */
    suspend fun getHtml(url: String, marqueur: String = "__next_f"): String = withContext(Dispatchers.IO) {
        val r1 = runCatching { lire(url) }.getOrElse { 0 to "" }
        if (ok(r1)) return@withContext r1.second
        if (r1.first != 0 && r1.first != 403 && r1.first != 503 && !estChallenge(r1.second))
            throw IOException("DramaExpress HTTP ${r1.first}")
        Log.i(TAG, "HTTP ${r1.first} (Cloudflare) → nouvelle session pour $url")
        sessionMutex.withLock {
            // Un autre appel a peut-être déjà renouvelé la session pendant qu'on attendait.
            val r = runCatching { lire(url) }.getOrElse { 0 to "" }
            if (ok(r)) return@withContext r.second
            renouvelerSession()
        }
        val r2 = runCatching { lire(url) }.getOrElse { 0 to "" }
        if (ok(r2)) return@withContext r2.second
        // Dernier recours : le cookie ne suffit pas à OkHttp (empreinte TLS) → la page rendue
        //   par le WebView lui-même (le HTML garde les données Next.js dans ses <script>).
        Log.w(TAG, "relance OkHttp ${r2.first} → lecture par le WebView : $url")
        val rv = sessionMutex.withLock { resolver ?: WebViewResolver(StreamFlixApp.instance).also { resolver = it } }
        val h = rv.get(url, silent = true, contentMarker = marqueur, markerTimeoutMs = 15_000L, challengeTimeoutMs = 20_000L)
        if (h.isBlank() || estChallenge(h) || h.contains("<body>Timeout</body>")) throw IOException("DramaExpress : Cloudflare")
        h
    }

    // ───────────────────────── catalogue ─────────────────────────

    private fun absolu(u: String) = if (u.startsWith("/")) BASE + u else u

    private fun coverDe(a: Element): String? {
        val img = a.selectFirst("img") ?: return null
        var src = img.attr("src").ifBlank { img.attr("srcset").substringBefore(" ") }
        if (src.contains("/_next/image")) src = URLDecoder.decode(src.substringAfter("url=").substringBefore("&"), "UTF-8")
        return src.takeIf { it.isNotBlank() }?.let { absolu(it) }
    }

    private fun cartes(racine: Element): List<Serie> =
        racine.select("a[href^=/$LANG/series/][aria-label]").mapNotNull { a ->
            val href = a.attr("href")
            if (href.contains("/episode-")) return@mapNotNull null
            val slug = href.removePrefix("/$LANG/series/").substringBefore('/').substringBefore('?')
            val titre = a.attr("aria-label").trim()
            if (slug.isBlank() || slug == "page" || titre.isBlank()) return@mapNotNull null
            val s = Serie(slug, titre, coverDe(a))
            series.merge(slug, s) { ancien, neuf -> if (neuf.cover == null) ancien else neuf }
            s
        }.distinctBy { it.slug }

    /** Liste complète, 29 séries par page (`/fr/series/page/<n>`). */
    suspend fun fetchCatalog(page: Int = 1): List<Serie> {
        val url = if (page <= 1) "$BASE/$LANG/series" else "$BASE/$LANG/series/page/$page"
        return cartes(Jsoup.parse(getHtml(url, "/$LANG/series/")))
    }

    suspend fun search(q: String): List<Serie> =
        cartes(Jsoup.parse(getHtml("$BASE/$LANG/search?q=" + URLEncoder.encode(q, "UTF-8"), "/$LANG/series/")))

    private val NOMS_SECTIONS = mapOf("Popüler" to "Populaires")

    /** Rangées de l'accueil du site (dans son ordre) + les dernières séries de la liste. */
    suspend fun rangeesAccueil(): List<Pair<String, List<String>>> {
        rangees?.let { (t, l) -> if (System.currentTimeMillis() - t < CACHE_MS && l.isNotEmpty()) return l }
        val out = ArrayList<Pair<String, List<String>>>()
        val doc = Jsoup.parse(getHtml("$BASE/$LANG", "/$LANG/series/"))
        for (sec in doc.select("section")) {
            val titre = sec.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
            val l = cartes(sec)
            if (titre.isNotBlank() && l.size >= 4) out.add((NOMS_SECTIONS[titre] ?: titre) to l.map { it.slug })
        }
        runCatching {
            val l = (fetchCatalog(1) + fetchCatalog(2)).distinctBy { it.slug }
            if (l.size >= 4) out.add("Toutes les séries" to l.map { it.slug })
        }.onFailure { Log.w(TAG, "liste : ${it.message}") }
        if (out.isNotEmpty()) rangees = System.currentTimeMillis() to out
        Log.i(TAG, "accueil : ${out.size} rangées, ${series.size} séries")
        return out
    }

    /** Genres du site (`/fr/category/<slug>`). Les libellés du site sont en partie en turc → noms FR. */
    val GENRES = linkedMapOf(
        "newly-added" to "Nouveautés",
        "revenge" to "Vengeance",
        "rich-ceo" to "PDG milliardaire",
        "power-comeback" to "Revanche et pouvoir",
        "gizli-kimlik" to "Identité secrète",
        "romantik" to "Romance",
        "dusmandan-aska" to "Des ennemis aux amants",
        "modern-ask-evlilik" to "Amour et mariage",
        "ask-ucgeni" to "Triangle amoureux",
        "guclu-kadin" to "Femme forte",
        "aile-dramasi" to "Drame familial",
        "fantasy" to "Fantastique",
        "yukselis-geri-donus" to "Ascension et retour",
        "second-chance" to "Seconde chance",
        "zaman-yolculugu" to "Voyage dans le temps",
        "tarihi-antik" to "Historique",
    )

    suspend fun fetchCategory(slug: String, page: Int = 1): List<Serie> {
        val url = if (page <= 1) "$BASE/$LANG/category/$slug" else "$BASE/$LANG/category/$slug/page/$page"
        return cartes(Jsoup.parse(getHtml(url, "/$LANG/series/")))
    }

    /** Titres « (Doublé) » / « doublage » = version française doublée. */
    fun estDouble(titre: String?) = titre?.contains("doubl", ignoreCase = true) == true

    // ───────────────────────── épisodes ─────────────────────────

    suspend fun fetchEpisodes(slug: String): Fiche {
        fiches[slug]?.let { (t, f) -> if (System.currentTimeMillis() - t < CACHE_MS) return f }
        val html = getHtml("$BASE/$LANG/series/$slug", "/episode-")
        val doc = Jsoup.parse(html)
        var titre: String? = null; var resume: String? = null; var image: String? = null
        for (s in doc.select("script[type=application/ld+json]")) {
            runCatching {
                val racine = JSONObject(s.data())
                val noeuds = racine.optJSONArray("@graph") ?: JSONArray().put(racine)
                for (i in 0 until noeuds.length()) {
                    val o = noeuds.optJSONObject(i) ?: continue
                    if (o.optString("@type") != "TVSeries") continue
                    titre = o.optString("name").ifBlank { null }
                    resume = o.optString("description").ifBlank { null }
                    image = when (val im = o.opt("image")) {
                        is String -> im
                        is JSONObject -> im.optString("url")
                        is JSONArray -> im.optString(0)
                        else -> null
                    }?.ifBlank { null }
                }
            }
        }
        val re = Regex("""/$LANG/series/${Regex.escape(slug)}/episode-(\d+)""")
        val eps = re.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.toSortedSet().toList()
        val connue = series[slug]
        val serie = Serie(
            slug,
            titre ?: connue?.titre ?: doc.selectFirst("h1")?.text().orEmpty(),
            connue?.cover ?: image ?: doc.selectFirst("meta[property=og:image]")?.attr("content"),
        )
        series[slug] = serie
        val f = Fiche(serie, resume, eps)
        if (eps.isNotEmpty()) fiches[slug] = System.currentTimeMillis() to f
        Log.i(TAG, "série $slug : ${eps.size} épisodes")
        return f
    }

    // ───────────────────────── flux ─────────────────────────

    private val cle by lazy { SecretKeySpec(Base64.decode(CLE_B64, Base64.DEFAULT), "AES") }

    private fun dechiffrer(enc: String): String? = runCatching {
        val b = Base64.decode(enc, Base64.DEFAULT)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, cle, GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8).trim()
    }.onFailure { Log.w(TAG, "déchiffrement : ${it.message}") }.getOrNull()

    fun episodeUrl(slug: String, n: Int) = "$BASE/$LANG/series/$slug/episode-$n"

    /** Liens de lecture d'un épisode (ordre du site = ordre de préférence). */
    suspend fun fetchStreamUrl(slug: String, n: Int): Flux {
        val k = "$slug|$n"
        flux[k]?.let { if (System.currentTimeMillis() - it.date < FLUX_MS) return it }
        val brut = getHtml(episodeUrl(slug, n), "sourceFirst")
        val t = brut.replace("\\\"", "\"").replace("\\/", "/")
        val i = t.indexOf("\"sourceFirst\":{")
        if (i < 0) { Log.w(TAG, "$k : pas de sourceFirst"); return Flux(emptyList(), null, System.currentTimeMillis()) }
        var bloc = t.substring(i, minOf(t.length, i + 8000))
        bloc.indexOf("\"nextSourceFirst\"").takeIf { it > 0 }?.let { bloc = bloc.substring(0, it) }
        val source = Regex("\"source\":\"([^\"]+)\"").find(bloc)?.groupValues?.get(1) ?: "DramaExpress"
        val chaine = bloc.substringAfter("\"chain\":[", "").substringBefore("]")
        val liens = Regex("""\{[^{}]*\}""").findAll(chaine).mapNotNull { m ->
            val o = m.value
            val enc = Regex("\"enc\":\"([^\"]+)\"").find(o)?.groupValues?.get(1) ?: return@mapNotNull null
            val type = Regex("\"type\":\"([^\"]+)\"").find(o)?.groupValues?.get(1).orEmpty()
            val src = Regex("\"source\":\"([^\"]+)\"").find(o)?.groupValues?.get(1) ?: source
            val url = dechiffrer(enc)?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Lien(url, type, src)
        }.distinctBy { it.url }.toList()
        // Hypothèse : `subtitle` = null (cas vu) | URL en clair | objet {enc} chiffré comme les liens.
        val apres = bloc.substringAfter("\"subtitle\":", "")
        val st = when {
            apres.startsWith("\"") -> apres.drop(1).substringBefore("\"").takeIf { it.startsWith("http") }
            apres.startsWith("{") -> Regex("\"enc\":\"([^\"]+)\"").find(apres.substringBefore("}"))?.groupValues?.get(1)?.let { dechiffrer(it) }
            else -> null
        }
        Log.i(TAG, "$k : ${liens.size} lien(s) $source ${liens.joinToString { it.type }}${if (st != null) " +ST" else ""}")
        return Flux(liens, st, System.currentTimeMillis()).also { if (liens.isNotEmpty()) flux[k] = it }
    }

    // 2026-10-23 (user : épisodes d'1 min 30, l'attente se voit) : pendant qu'on regarde l'épisode n,
    //   on lit déjà la page de l'épisode n+1 (lien déchiffré en cache) → démarrage quasi immédiat.
    private val prechauffage = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun prechauffer(slug: String, n: Int) {
        flux["$slug|$n"]?.let { if (System.currentTimeMillis() - it.date < FLUX_MS) return }
        prechauffage.launch { runCatching { fetchStreamUrl(slug, n) } }
    }

    // Le type annoncé ment parfois (MoboReels : « mp4 » pour un .m3u8) → l'extension de l'URL prime.
    fun estMp4(l: Lien) = !l.url.substringBefore('?').endsWith(".m3u8", true) &&
        (l.type.equals("mp4", true) || l.url.substringBefore('?').endsWith(".mp4", true))

    private val ENTETES_RELAIS get() = mapOf("Referer" to "$BASE/", "Origin" to BASE, "User-Agent" to NetworkClient.USER_AGENT)

    private fun sonderM3u8(url: String, entetes: Map<String, String>): Boolean = runCatching {
        val req = Request.Builder().url(url).apply { entetes.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r -> r.isSuccessful && r.body?.source()?.peek()?.readUtf8(7) == "#EXTM3U" }
    }.getOrDefault(false)

    /** Video jouable pour un lien : direct si l'hôte accepte, sinon via le relais du site. */
    suspend fun video(l: Lien, sousTitre: String?): Video? = withContext(Dispatchers.IO) {
        val st = listOfNotNull(sousTitre?.let { Video.Subtitle(label = "Français", file = it, default = true, initialDefault = true) })
        if (estMp4(l)) return@withContext Video(source = l.url, subtitles = st,
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT), type = "video/mp4")
        val direct = mapOf("User-Agent" to NetworkClient.USER_AGENT)
        if (sonderM3u8(l.url, direct)) return@withContext Video(source = l.url, subtitles = st, headers = direct, type = "application/vnd.apple.mpegurl")
        val relaye = RELAIS + l.url.replaceFirst("://", "/")
        if (sonderM3u8(relaye, ENTETES_RELAIS)) return@withContext Video(source = relaye, subtitles = st, headers = ENTETES_RELAIS, type = "application/vnd.apple.mpegurl")
        Log.w(TAG, "lien refusé direct + relais : ${l.source} ${l.type}")
        null
    }

    /** Secours : lecteur du site dans un WebView invisible, capture du flux qu'il demande. */
    suspend fun videoParLecteur(slug: String, n: Int): Video? {
        val r = WebViewStreamResolver.resolve(
            episodeUrl(slug, n), referer = "$BASE/$LANG", userAgent = ua, timeoutMs = 25_000L,
            captureMp4 = true, clickPlay = true, attach = true,
        ) ?: return null
        return Video(source = r.url, headers = r.headers,
            type = if (r.url.substringBefore('?').endsWith(".mp4", true)) "video/mp4" else "application/vnd.apple.mpegurl")
    }
}
