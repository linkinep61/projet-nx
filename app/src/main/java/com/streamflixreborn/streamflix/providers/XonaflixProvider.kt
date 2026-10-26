package com.streamflixreborn.streamflix.providers

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * XonaflixProvider — backup NATIF par TITRE (xonaflix.space, ajouté le 2026-10-28).
 *
 * D'après le diagnostic « appareils » dans le Chrome du user (cf. RELAIS.md 2026-10-28) :
 *   - ~8 000 films + 900 séries, ajouts récents, contenu FR réel (VF / VOSTFR).
 *   - Même MOTEUR Laravel+Livewire que Papadustream V2 (mêmes chemins, mêmes slugs) mais
 *     AUTRE site et AUTRES lecteurs → pas un doublon.
 *   - Aucun Cloudflare sur les pages : le « CF avant la lecture » n'est qu'un widget Turnstile
 *     côté AFFICHAGE du lecteur (après un mur de pubs) ; les liens des lecteurs sont déjà dans
 *     le HTML de la fiche → aucun WebViewResolver nécessaire.
 *   - Requêtes (toutes GET, sans cookie, en-têtes de navigateur normaux) :
 *       Recherche : /search?q=<titre> → liens /movie/<slug> et /tv-show/<slug>
 *       Fiche film : /movie/<slug> — Épisode : /episode/<slug>/<saison>-<épisode>
 *       Lecteurs : composant Livewire wire:snapshot (memo.name == "watch-component"),
 *         data.videos[0] = liste d'objets enveloppés [ {label, link, type, moviez?}, meta ].
 *   - Serveurs : « VF » = lien /embed/<id>?v=&e=&t= SIGNÉ (expire en quelques heures) dont
 *     la page construit l'URL finale, un mp4 du CDN cdnNN.zen-blk.store (même CDN que
 *     Movix/SwiftFlow — Referer exigé, cf. SwiftFlowExtractor) ; « VOSTFR » = vidzy.org
 *     (résolu par VidzyExtractor, déjà en-app) ; « VF 2 » (moviez: true) ÉCARTÉ
 *     provisoirement : résolution côté serveur inconnue (relais : « plus tard »).
 *
 * Source BACKUP uniquement (appelée par le registre par titre). Comme tout backup : match
 * TITRE STRICT (« pas de serveur plutôt que le mauvais film »), retourne [] si rien ne
 * matche. Les liens /embed/ expirant vite, l'id du serveur transporte le chemin de la page
 * fiche/épisode : getVideo relit la fiche pour régénérer un lien frais si besoin.
 */
object XonaflixProvider {
    private const val TAG = "Xonaflix"
    // .space = domaine courant ; .online = adresse de secours annoncée par le site lui-même.
    private val BASES = listOf("https://xonaflix.space", "https://xonaflix.online")
    @Volatile private var baseIdx = 0
    private val base get() = BASES[baseIdx]

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** GET complet : (code, corps) — null uniquement si injoignable (réseau/timeout). */
    private suspend fun getRaw(url: String, referer: String? = null): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", WebViewResolver.STEALTH_UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .apply { if (referer != null) header("Referer", referer) }
                    .build()
                client.newCall(req).execute().use { resp ->
                    val corps = resp.body?.string()
                    if (corps == null) null else resp.code to corps
                }
            } catch (e: Exception) { Log.w(TAG, "GET $url failed: ${e.message}"); null }
        }

    /** GET d'un chemin relatif sur la base courante ; injoignable/5xx → bascule .space/.online. */
    private suspend fun getPage(chemin: String): String? {
        var essais = 0
        while (essais++ < BASES.size) {
            val res = getRaw(base + chemin)
            if (res != null) {
                val (code, corps) = res
                if (code in 200..399) return corps
                // 40x = la page n'existe pas (slug/épisode absent) : inutile de changer de domaine.
                if (code in 400..499) return null
                // 5xx → on tente l'autre domaine.
            }
            baseIdx = (baseIdx + 1) % BASES.size
            Log.i(TAG, "bascule de domaine → $base")
        }
        return null
    }

    // ══ Matching de titre STRICT (copie de PapadustreamV2Provider, même moteur/same slugs) ══

    private fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    // Mots de DÉCORATION (langue/qualité/format) ignorés dans le matching — cf. PapaV2.
    private val DECORATION = setOf(
        "vf", "vostfr", "vo", "truefrench", "french", "multi", "complet", "complete",
        "streaming", "stream", "gratuit", "film", "films", "serie", "series", "saison",
        "season", "episode", "integrale", "integral", "voir", "regarder", "hd", "full"
    )
    private fun sigWords(s: String): Set<String> = norm(s).split(" ")
        .filter { it.length >= 2 && it !in DECORATION && !it.matches(Regex("(19|20)\\d{2}")) && it.any { c -> !c.isDigit() } }
        .toSet()

    private fun wordMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val s = if (a.length <= b.length) a else b; val l = if (a.length <= b.length) b else a
        return s.length >= 4 && l.startsWith(s) && (l.length - s.length) <= 2  // pluriel/variante
    }

    /** Match BIDIRECTIONNEL strict (rejette « FROM » ⊄ « Beauty From Pain » et inversement). */
    private fun titleMatches(candidate: String, query: String): Boolean {
        val qw = sigWords(query)
        val cw = sigWords(candidate)
        if (qw.isEmpty() || cw.isEmpty()) {
            val nc = norm(candidate).replace(" ", ""); val nq = norm(query).replace(" ", "")
            return nc.isNotEmpty() && nc == nq
        }
        val extra = cw.filter { c -> qw.none { q -> wordMatch(c, q) } }
        if (extra.isNotEmpty()) return false
        val covered = qw.count { q -> cw.any { c -> wordMatch(q, c) } }
        return covered.toDouble() / qw.size >= 0.5
    }

    // ══ Parsing des serveurs (composant Livewire wire:snapshot) ══

    private data class ServeurXona(val label: String, val link: String, val type: String, val moviez: Boolean)

    /**
     * Extrait les lecteurs de la fiche. data.videos[0] = liste d'objets Livewire enveloppés
     * [ {label, link, type, moviez?}, meta ]. Voie JSONObject d'abord, repli regex si le
     * snapshot ne parse pas (échappements Livewire variables).
     */
    private fun parseServeurs(pageHtml: String): List<ServeurXona> {
        val snapshots = try {
            Jsoup.parse(pageHtml).allElements.asSequence()
                .mapNotNull { el -> el.attr("wire:snapshot").takeIf { it.isNotBlank() } }
                .filter { it.contains("watch-component") }
                .toList()
        } catch (_: Exception) { emptyList() }

        val out = ArrayList<ServeurXona>()
        for (snap in snapshots) {
            var parseOk = false
            try {
                val videos = JSONObject(snap).getJSONObject("data").getJSONArray("videos").getJSONArray(0)
                for (i in 0 until videos.length()) {
                    val obj = videos.optJSONArray(i)?.optJSONObject(0) ?: continue
                    val label = obj.optString("label")
                    val link = obj.optString("link")
                    if (label.isBlank() || link.isBlank()) continue
                    out += ServeurXona(label, link, obj.optString("type"), obj.optBoolean("moviez", false))
                }
                parseOk = true
            } catch (_: Exception) { /* repli regex ci-dessous */ }
            if (!parseOk) {
                // Repli : ordre des champs observé dans le diagnostic (label, link, type).
                val texte = snap.replace("\\/", "/").replace("\\u0026", "&").replace("\\u002F", "/")
                Regex("\"label\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"link\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"type\"\\s*:\\s*\"([^\"]+)\"")
                    .findAll(texte).forEach { m ->
                        out += ServeurXona(
                            m.groupValues[1], m.groupValues[2], m.groupValues[3],
                            Regex("\"moviez\"\\s*:\\s*true").containsMatchIn(m.value),
                        )
                    }
            }
        }
        return out.distinctBy { it.link }
    }

    /** Fiche → serveurs du registre. Id = chemin de la page (pour régénérer le lien à la lecture). */
    private fun emitServers(pageHtml: String, pagePath: String): List<Video.Server> {
        val out = ArrayList<Video.Server>()
        val seen = HashSet<String>()
        for (s in parseServeurs(pageHtml)) {
            if (s.moviez) continue  // « VF 2 » : résolution côté serveur inconnue → plus tard.
            if (!s.link.startsWith("http") || !seen.add(s.link)) continue
            out.add(Video.Server(
                id = "xflx::" + URLEncoder.encode(pagePath, "UTF-8") + "::" + URLEncoder.encode(s.label, "UTF-8"),
                name = "Xona ${s.label}",
                src = s.link,
            ))
        }
        return out
    }

    /**
     * Backup par titre. Recherche, match STRICT bidirectionnel, épisode ciblé pour les séries,
     * gate d'année best-effort pour les films. Retourne [] si rien ne matche.
     */
    suspend fun fetchXonaflixBackup(
        title: String,
        year: Int? = null,
        season: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val isSeries = season > 0 || episode > 0
        // Diagnostic : recherche = GET /search?q=<titre> (espaces = %20, le + casse la recherche).
        val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val searchHtml = getPage("/search?q=$q") ?: return@withContext emptyList()

        // Candidats : /movie/<slug> (films) ET /tv-show/<slug> (séries) + garde de type.
        val candidates = try {
            Jsoup.parse(searchHtml, base).select("a[href*=/movie/], a[href*=/tv-show/]")
                .mapNotNull { a ->
                    val m = Regex("/(movie|tv-show)/([a-z0-9-]+)", RegexOption.IGNORE_CASE).find(a.attr("href"))
                        ?: return@mapNotNull null
                    m.groupValues[1].lowercase() to m.groupValues[2]
                }
                .distinctBy { "${it.first}/${it.second}" }
        } catch (_: Exception) { emptyList() }
        val typed = candidates.filter { (type, _) -> if (isSeries) type == "tv-show" else type == "movie" }

        val match = typed.firstOrNull { (_, slug) ->
            val slugTitle = slug.replace("-", " ")
            titleMatches(slugTitle, title) && titleMatches(title, slugTitle)
        } ?: return@withContext emptyList()

        // Page qui porte les serveurs : épisode ciblé pour une série, fiche pour un film.
        val pagePath = if (isSeries) "/episode/${match.second}/$season-$episode" else "/movie/${match.second}"
        val pageHtml = getPage(pagePath) ?: return@withContext emptyList()

        // Gate année (films) — best-effort sur le <head> (JSON-LD/méta). Ne gate QUE si l'année
        //   y figure. ⚠ Patterns à caler sur les captures HTML (outils/captures/xonaflix_*.html).
        if (!isSeries && year != null) {
            val head = pageHtml.substringBefore("</head>")
            val relYear = listOf(
                Regex("\"(?:releaseYear|release_year|datePublished)\"\\s*:\\s*\"?((?:19|20)\\d{2})"),
                Regex("<meta[^>]+(?:datePublished|og:release_date)[^>]+content=\"((?:19|20)\\d{2})\""),
            ).firstNotNullOfOrNull { it.find(head)?.groupValues?.get(1)?.toIntOrNull() }
            if (relYear != null && kotlin.math.abs(relYear - year) > 1) {
                Log.i(TAG, "rejet année : fiche=$relYear cible=$year ('${match.second}')")
                return@withContext emptyList()
            }
        }

        emitServers(pageHtml, pagePath)
    }

    /**
     * Lecture. Liens externes (vidzy…) → Extractors in-app. Lien /embed/ xonaflix (mp4
     * signé) : la page /embed/ construit l'URL finale ; si le lien signé a expiré (403/404),
     * on relit la fiche (chemin stocké dans l'id) et on régénère un lien frais.
     */
    suspend fun getVideo(server: Video.Server): Video {
        // Décodage de l'id : xflx::<chemin de la page>::<label>
        val morceaux = server.id.removePrefix("xflx::").split("::")
        val pagePath = morceaux.getOrNull(0)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        val label = morceaux.getOrNull(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }

        // Serveur externe (vidzy.org…) → extracteurs existants (VidzyExtractor gère vidzy.*).
        if (!server.src.contains("xonaflix", ignoreCase = true))
            return Extractor.extract(server.src, server)

        // 1) Le lien /embed/ est peut-être encore valide.
        resoudreEmbed(server.src, pagePath)?.let { return it }

        // 2) Lien expiré → on relit la fiche et on régénère un lien frais.
        if (pagePath != null) {
            val pageHtml = getPage(pagePath) ?: throw Exception("Xonaflix : fiche injoignable ($pagePath)")
            val cible = parseServeurs(pageHtml)
                .filter { !it.moviez && it.link.startsWith("http") && it.link.contains("xonaflix", true) }
                .firstOrNull { label == null || it.label.equals(label, ignoreCase = true) }
                ?: throw Exception("Xonaflix : lecteur « ${label ?: "?"} » introuvable sur la fiche")
            resoudreEmbed(cible.link, pagePath)?.let { return it }
        }
        throw Exception("Xonaflix : flux introuvable (${server.src.take(60)})")
    }

    /**
     * Page /embed/ (lecteur Plyr) → URL mp4/m3u8 finale. 2026-10-28 (captures
     * outils/captures/xonaflix_embed.html) : l'URL du flux est CACHÉE en base64 dans
     * <input type="hidden" id="encrypted-source"> (décodée par atob() dans le JS) — le HTML
     * ne contient AUCUNE URL en clair. Flux observé : mp4 du CDN zen-blk.store avec le
     * paramètre signé ff= (même CDN et même signature que Movix/SwiftFlow) → mêmes
     * en-têtes que SwiftFlow : UA stealth + Referer du lecteur, sans Origin.
     */
    private suspend fun resoudreEmbed(embedUrl: String, pagePath: String?): Video? {
        val referer = if (pagePath != null) base + pagePath else "$base/"
        val res = getRaw(embedUrl, referer = referer) ?: return null
        val (code, corps) = res
        if (code !in 200..399) return null

        // 1) encrypted-source : URL en base64, décodée ici (équivalent atob()).
        val url = Regex("id=\"encrypted-source\"[^>]*value=\"([^\"]+)\"")
            .find(corps)?.groupValues?.get(1)
            ?.let { brut -> runCatching {
                String(android.util.Base64.decode(brut, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() }
            ?.takeIf { it.startsWith("http") }
            // 2) Repli (le lecteur évolue) : URL en clair dans le JS — CDN de la famille
            //    Movix d'abord, puis mp4/m3u8 quelconque. URLs JSON-échappées décodées avant.
            ?: run {
                val texte = corps.replace("\\/", "/").replace("\\u0026", "&").replace("\\u002F", "/")
                listOf(
                    Regex("https?://[a-z0-9.-]*(?:zen-blk|vida-loka|citron-edge|mongo-loko)[a-z0-9.-]*[^\"'\\s\\\\<>]+?\\.(?:mp4|m3u8)[^\"'\\s\\\\<>]*", RegexOption.IGNORE_CASE),
                    Regex("https?://[^\"'\\s\\\\<>]+\\.mp4[^\"'\\s\\\\<>]*"),
                    Regex("https?://[^\"'\\s\\\\<>]+\\.m3u8[^\"'\\s\\\\<>]*"),
                ).firstNotNullOfOrNull { rx -> rx.find(texte)?.value }
            }
            ?.takeIf { !it.contains("/troll/") }
            ?: run { Log.w(TAG, "resoudreEmbed : source introuvable dans /embed/ (code=$code)"); return null }

        // Les chemins du CDN contiennent des accents (ex. « Dune-Prémière-Partie ») →
        // percent-encodage des caractères non-ASCII (toASCIIString), réservés conservés.
        val urlAscii = runCatching { java.net.URI(url).toASCIIString() }.getOrDefault(url)

        Log.i(TAG, "embed résolu : ${urlAscii.substringBefore("?").takeLast(60)}")
        return Video(
            source = urlAscii,
            type = if (urlAscii.contains(".m3u8", true)) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4,
            headers = mapOf(
                "User-Agent" to WebViewResolver.STEALTH_UA,
                "Referer" to embedUrl,
            ),
        )
    }
}
