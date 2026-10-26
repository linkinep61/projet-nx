package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.sport.SportEvent
import com.streamflixreborn.streamflix.sport.StreamSource
import com.streamflixreborn.streamflix.sport.TtlCache
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 2026-10-28 (user, cf. RELAIS « SPORT : ajouter xonaflix ») : matchs et chaînes de sport de
 * xonaflix.space, versés dans le dossier Sport (compétitions + bouquets, cf. SportHub).
 *
 * Recette vérifiée le 2026-10-28 dans le Chrome du user (et capture
 * outils/captures/northlive_player.html) :
 *  • matchs : `GET /tv` (HTML simple, sans cookie ni Turnstile) → liens
 *    `/live-broadcast/<slug>` dont le texte = « <LIGUE> <HH:MM> <Équipe1> VS <Équipe2>
 *    Regarder · <Chaîne> » (heure de Paris, jour = aujourd'hui) ;
 *  • chaînes fixes : `GET /tv?cat=Sport&page=1..4` (≈ 72 chaînes), mêmes liens, nom du lien
 *    moins « LIVE » / « France » / « IPTV » / « Regarder » ; cartes de match (texte avec
 *    « VS ») ignorées ; arrêt dès qu'une page n'apporte aucun slug nouveau ;
 *  • lecture NATIVE (exigence du user : PAS de WebView), northlive est de la même famille que
 *    SwiftFlow (Movix) :
 *      1. clé API : identique pour TOUTES les chaînes, absente de /tv. Lue dans une page
 *         `/live-broadcast/<slug>` : attribut `wire:snapshot` (JSON échappé en entités HTML)
 *         dont memo.name == « northlive-watch-component » → data.channel[0].player_url =
 *         `https://northlive.lol/api/v1/index.php?route=tv/<slug>/player&api_key=<CLÉ>`.
 *         Jamais écrite en dur ; gardée ~1 h ;
 *      2. `GET <player_url>` → `const PLAY_TOKEN = "<jeton>";` (valable quelques heures) ;
 *      3. `POST https://northlive.lol/api/v1/index.php?route=play_url&api_key=<CLÉ>` avec
 *         `Content-Type: application/json`, `Authorization: Bearer <CLÉ>` et le corps
 *         `{"slug","api_key","play_token"}` → JSON `{success, url, direct}` ;
 *      4. url relative (`/api/tv_proxy.php?tok=…&pt=…`) → base northlive.lol, plus `pt=<jeton>`
 *         si absent. Flux HLS lu par le lecteur ONYX avec `Referer` = player_url sur la
 *         playlist ET les segments (mesuré : 403 sans lui ; cookies inutiles).
 *
 *  • 2026-10-28 (suite, user, cf. RELAIS « CHAÎNES TV xonaflix ») : les catégories NON
 *    sportives de /tv (~924 chaînes) deviennent la plateforme « XonaFlix » du dossier
 *    « Chaînes TV » du TV Hub (cf. SportHub) : sous-dossiers = catégories du site,
 *    chargés au clic, chaînes jamais fusionnées avec celles des autres sources
 *    (les étiquettes de pays du site ne sont pas fiables, cf. Sport). Même lecture
 *    native ci-dessous, rien de dupliqué.
 */
object XonaFlix {

    const val ID = "xonaflix"
    private const val TAG = "SportXonaFlix"
    /** Préfixe des serveurs : `xonaflix::<slug>` (le slug northlive, même slug que xonaflix). */
    const val PREFIX = "xonaflix::"
    private const val BASE = "https://xonaflix.space"
    private const val NORTH = "https://northlive.lol"
    private const val COMPOSANT_LECTEUR = "northlive-watch-component"
    private const val TTL_CLE_MS = 60 * 60_000L
    private val PARIS = ZoneId.of("Europe/Paris")

    /** Chaîne fixe du catalogue de xonaflix (slug = dernier segment du lien). */
    data class Chaine(val slug: String, val nom: String, val logo: String = "")

    /**
     * 2026-10-28 (user : « fais le tri par toi-même ») — chaînes Sport dont l'audio est VÉRIFIÉ en
     * français : langue lue dans le flux lui-même (descripteur de langue ISO 639 de la PMT des
     * segments), chaîne par chaîne, dans le Chrome du user. Les étiquettes « France » du site sont
     * fausses (DAZN 2 = portugais, « Canal+ Ligue 1 » = espagnol, DAZN 3 / Red Bull = anglais…).
     * Hors de cette liste (étrangère, langue non déclarée ou muette au test) → « Foot d'ailleurs ».
     */
    val FRANCAIS = setOf(
        "bein-sports-1-fhd-b", "bein-sports-2-fhd-b", "bein-sports-3-fhd-b",
        "bein-sports-max-4-fhd-event-only-s", "bein-sports-max-8-fhd-event-only-s",
        "canal-sport-fhd-b", "canal-sport-1-hd-b", "canal-sport-3-hd-b", "canal-sport-360-c",
        "canal-sport-5-hd-b", "canal-sport-sd-b", "canal-top-14-hd-b",
        "be-eleven-pro-league-3-hd-b", "be-elevensport-3-hd-b",
        "dazn-ligue-1-live-3-sd-b", "ligue1-3-c",
        "rmc-sport-1-fhd-b", "rmc-sport-4-fhd-b", "rmc-sport-5-s", "rmc-sport-7-s",
        "l-quipe",
    )

    /** Catégorie du catalogue TV (liens `?cat=` de /tv), hors Sport (déjà dans le dossier Sport). */
    data class Categorie(val nom: String, val cle: String)

    private val cacheMatchs = TtlCache<List<SportEvent>>(2 * 60_000L)
    private val cacheChaines = TtlCache<List<Chaine>>(30 * 60_000L)

    // 2026-10-28 (user, cf. RELAIS « CHAÎNES TV xonaflix ») : catégories TV (chaînes non
    //   sportives) — une plateforme à part dans le dossier « Chaînes TV » du TV Hub.
    private val cacheCategories = TtlCache<List<Categorie>>(30 * 60_000L)
    /** Un cache par catégorie ouverte (chargement au clic, règle du TV Hub). */
    private val cachesChainesTv = ConcurrentHashMap<String, TtlCache<List<Chaine>>>()
    /** Chaînes TV déjà vues (slug → chaîne) : nom du serveur à la lecture, cf. [nomAffiche]. */
    private val annuaireTv = ConcurrentHashMap<String, Chaine>()
    /** Pages maximum parcourues par catégorie TV (IPTV ≈ 421 chaînes ≈ 21 pages : ceinture
     *  contre une boucle si le site renvoyait toujours des slugs nouveaux). */
    private const val MAX_PAGES_TV = 25

    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
    }

    private fun get(url: String, referer: String? = null): String {
        val req = Request.Builder().url(url).apply { referer?.let { header("Referer", it) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code} : $url")
            return r.body?.string().orEmpty()
        }
    }

    // ───────────────────────────────────────────────────────── listes

    /** « SERIE A 18:30 Milan VS Bologna Regarder · DAZN 1 » → ligue, heure, équipes, chaîne. */
    private val RX_MATCH = Regex(
        "^(.+?)\\s+(\\d{1,2})[:h](\\d{2})\\s+(.+?)\\s+vs\\.?\\s+(.+?)\\s+regarder(?:\\s*·\\s*(.+))?$",
        RegexOption.IGNORE_CASE,
    )

    /** Matchs « à l'affiche » (cache 2 min). Liste vide = rien à afficher, ce n'est pas une panne. */
    suspend fun matchs(): List<SportEvent> = cacheMatchs.get { chargerMatchs() }

    private suspend fun chargerMatchs(): List<SportEvent> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get("$BASE/tv"), BASE)
        val vus = HashSet<String>()
        val out = ArrayList<SportEvent>()
        for (a in doc.select("a[href*=\"/live-broadcast/\"]")) {
            val m = RX_MATCH.find(a.text()) ?: continue // menus, chaînes… : pas des cartes de match
            val (ligue, h, mn, dom, ext, chaine) = m.destructured
            val lien = a.absUrl("href").ifBlank { a.attr("href") }
            val slug = lien.substringAfterLast('/')
            if (slug.isBlank() || !vus.add("$slug·$dom·$ext")) continue
            val ev = runCatching {
                // Heure de Paris, jour = aujourd'hui (mesuré : la page ne donne que HH:MM).
                val debut = LocalDate.now(PARIS)
                    .atTime(LocalTime.of(h.toInt(), mn.toInt()))
                    .atZone(PARIS).toInstant().toEpochMilli()
                // La source ne donne pas de statut : « live » de l'heure prévue à + 4 h.
                val enCours = debut <= System.currentTimeMillis() &&
                    System.currentTimeMillis() < debut + 4 * 3600_000L
                SportEvent(
                    id = slug, provider = ID,
                    title = "$dom VS $ext",
                    league = ligue.trim(), category = "", poster = "",
                    startsAt = debut, endsAt = null,
                    status = if (enCours) "live" else "upcoming", viewers = 0,
                    // La chaîne diffusante (« DAZN 1 ») sert de nom de serveur (cf. nomAffiche).
                    sources = listOf(StreamSource(name = chaine.trim().ifBlank { "Direct" }, embedUrl = lien)),
                    // 2026-10-28 (user : « les quatre live ne sont pas français », puis « fais le tri
                    //   par toi-même ») : français seulement si la chaîne diffusante est VÉRIFIÉE
                    //   (cf. FRANCAIS) ; sinon « Foot d'ailleurs », sauf si une source française l'a aussi.
                    embedUrl = lien, francais = slug in FRANCAIS,
                )
            }.getOrNull() ?: continue
            out += ev
        }
        Log.i(TAG, "${out.size} matchs xonaflix (${out.count { it.enDirect }} en direct)")
        out
    }

    /** Chaînes fixes du catalogue Sport (cache 30 min ; pages 1..4, arrêt dès qu'une page
     *  n'apporte aucun slug nouveau). */
    suspend fun chaines(): List<Chaine> =
        cacheChaines.get { chargerChaines("Sport", PAGES_CATEGORIE_SPORT, false) }

    /** Pages parcourues pour la catégorie Sport (≈ 72 chaînes, mesuré le 2026-10-28). */
    private const val PAGES_CATEGORIE_SPORT = 4

    // ───────────────────────────────────────────── catégories TV (dossier « Chaînes TV »)

    /** Catégories NON sportives du catalogue TV (cache 30 min ; ordre du site :
     *  IPTV, Musique, Généraliste…). « Sport » est exclu : ses chaînes sont déjà
     *  dans le dossier Sport (décision du user, cf. RELAIS du 2026-10-28). */
    suspend fun categories(): List<Categorie> = cacheCategories.get { chargerCategories() }

    /** Dernière liste de catégories connue, même périmée (affichage sans réseau). */
    fun categoriesPretes(): List<Categorie>? = cacheCategories.derniere()

    /** Nom affiché d'une catégorie, d'après la dernière liste connue (null = inconnue). */
    fun nomCategorie(cle: String): String? =
        cacheCategories.derniere()?.firstOrNull { it.cle == cle }?.nom

    private val RX_CAT = Regex("[?&]cat=([^&\"']+)")

    private suspend fun chargerCategories(): List<Categorie> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get("$BASE/tv"), BASE)
        val vues = HashSet<String>()
        val out = ArrayList<Categorie>()
        for (a in doc.select("a[href*=\"cat=\"]")) {
            // Liens du site uniquement (résolus contre /tv) : un « ?cat= » externe ne crée
            //   pas de catégorie fantôme.
            val lien = a.absUrl("href")
            if (!lien.startsWith("$BASE/tv")) continue
            val brut = RX_CAT.find(lien)?.groupValues?.get(1) ?: continue
            val nom = runCatching { URLDecoder.decode(brut, "UTF-8") }.getOrDefault(brut).trim()
            val cle = cleCategorie(nom)
            if (nom.isEmpty() || cle.isEmpty() || cle == "sport" || !vues.add(cle)) continue
            out += Categorie(nom, cle)
        }
        if (out.isEmpty()) throw Exception("aucune catégorie TV lue")
        Log.i(TAG, "${out.size} catégories TV xonaflix : ${out.joinToString { it.nom }}")
        out
    }

    /** Clé interne d'une catégorie : « Art de vivre » → « artdevivre » (ids de sous-dossiers). */
    private fun cleCategorie(nom: String): String = Normalizer
        .normalize(nom, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Chaînes d'une catégorie TV (cache 30 min par catégorie, logos lus). [cle] = clé
     *  interne (cf. [Categorie.cle]) ; exception si la catégorie a disparu du site. */
    suspend fun chainesTv(cle: String): List<Chaine> {
        val cat = cacheCategories.derniere()?.firstOrNull { it.cle == cle }?.nom
            ?: categories().firstOrNull { it.cle == cle }?.nom
            ?: throw Exception("catégorie TV inconnue : $cle")
        val liste = cachesChainesTv.getOrPut(cle) { TtlCache(30 * 60_000L) }
            .get { chargerChaines(cat, MAX_PAGES_TV, true) }
        if (annuaireTv.size > 3_000) annuaireTv.clear() // ceinture mémoire (≈ 900 chaînes au total)
        liste.forEach { annuaireTv[it.slug] = it }
        return liste
    }

    private val RX_BRUIT_CHAINE = Regex("\\b(?:live|france|iptv|regarder|watch)\\b", RegexOption.IGNORE_CASE)

    /** « beIN SPORTS 1 France LIVE Regarder » → « beIN SPORTS 1 » (mots parasites du site). */
    private fun nomChaine(texte: String): String =
        texte.replace(RX_BRUIT_CHAINE, " ")
            .replace(Regex("\\s*[·|]\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '·', '|', '-', ',', '.')

    /** Chaînes fixes d'une catégorie du catalogue : pages 1..[maxPages], arrêt dès qu'une
     *  page n'apporte aucun slug nouveau (les logos ne sont lus que pour les catégories TV). */
    private suspend fun chargerChaines(cat: String, maxPages: Int, logos: Boolean): List<Chaine> =
        withContext(Dispatchers.IO) {
            // « Sport » ne change pas à l'encodage : la requête du dossier Sport reste la même.
            val catUrl = URLEncoder.encode(cat, "UTF-8")
            val vus = HashSet<String>()
            val parSlug = LinkedHashMap<String, Chaine>()
            for (page in 1..maxPages) {
                val ajoutes = runCatching {
                    val doc = Jsoup.parse(get("$BASE/tv?cat=$catUrl&page=$page"), BASE)
                    var n = 0
                    for (a in doc.select("a[href*=\"/live-broadcast/\"]")) {
                        val texte = a.text()
                        // Ces pages mélangent cartes de matchs et chaînes : les cartes portent « VS ».
                        if (texte.contains("VS", ignoreCase = true)) continue
                        val slug = a.absUrl("href").substringAfterLast('/')
                        if (slug.isBlank() || !vus.add(slug)) continue
                        val nom = nomChaine(texte)
                        if (nom.isBlank()) continue
                        parSlug[slug] = Chaine(slug, nom, if (logos) logoDe(a) else "")
                        n++
                    }
                    n
                }.getOrDefault(0)
                if (ajoutes == 0) break
            }
            if (parSlug.isEmpty()) throw Exception("aucune chaîne lue ($cat)")
            Log.i(TAG, "${parSlug.size} chaînes xonaflix ($cat)")
            parSlug.values.toList()
        }

    /** Logo de la carte s'il y en a un (src paresseux « data-src » accepté) ; vide sinon. */
    private fun logoDe(a: Element): String {
        val img = a.selectFirst("img") ?: return ""
        for (attr in listOf("src", "data-src", "data-lazy-src")) {
            val u = img.absUrl(attr).ifBlank { img.attr(attr) }
            if (u.startsWith("http")) return u
        }
        return ""
    }

    // ───────────────────────────────────────────────────────── lecture (native)

    fun estServeur(server: Video.Server) = server.id.startsWith(PREFIX)

    /** Serveur d'une tuile xonaflix ; [id] = « m::<slug> » (match) ou <slug> (chaîne).
     *  Aucun appel réseau ici : la résolution northlive se fait à la lecture ([video]). */
    suspend fun serveurs(id: String): List<Video.Server> {
        val slug = id.removePrefix("m::")
        return listOf(Video.Server(id = PREFIX + slug, name = nomAffiche(slug), src = "$BASE/live-broadcast/$slug"))
    }

    private fun nomAffiche(slug: String): String {
        // Chaîne TV (dossier « Chaînes TV ») : nom gardé au chargement de sa catégorie.
        annuaireTv[slug]?.let { return it.nom }
        cacheChaines.derniere()?.firstOrNull { it.slug == slug }?.let { return it.nom }
        // Match : le nom du serveur = la chaîne diffusante (« DAZN 1 »).
        cacheMatchs.derniere()?.firstOrNull { it.id == slug }
            ?.let { return it.sources.firstOrNull()?.name ?: "Direct" }
        return "Direct"
    }

    /**
     * Flux HLS d'une chaîne ou d'un match (lecteur ONYX, pas de WebView). Un échec (clé ou jeton
     * périmé) relit la page de la chaîne une fois puis réessaie.
     */
    suspend fun video(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val slug = server.id.removePrefix(PREFIX).substringBefore("::")
        runCatching { lireFlux(slug) }.recoverCatching { e ->
            Log.w(TAG, "lecture $slug KO (${e.message}) : clé relue puis nouvel essai")
            cleApiCache = null
            lireFlux(slug)
        }.getOrThrow()
    }

    private val RX_SNAPSHOT = Regex("wire:snapshot=\"([^\"]*)\"")
    private val RX_CLE_API = Regex("[?&]api_key=([^&]+)")
    private val RX_PLAY_TOKEN = Regex("PLAY_TOKEN\\s*=\\s*\"([^\"]+)\"")

    @Volatile private var cleApiCache: Pair<Long, String>? = null
    private val verrouCle = Mutex()

    /** player_url dans le wire:snapshot du composant lecteur (JSON échappé en entités HTML). */
    private fun playerUrlDans(html: String): String? {
        for (m in RX_SNAPSHOT.findAll(html)) {
            val j = runCatching { JSONObject(decoderEntites(m.groupValues[1])) }.getOrNull() ?: continue
            if (j.optJSONObject("memo")?.optString("name") != COMPOSANT_LECTEUR) continue
            val url = j.optJSONObject("data")?.optJSONArray("channel")?.optJSONObject(0)
                ?.optString("player_url").orEmpty()
            if (url.startsWith("http")) return url
        }
        return null
    }

    /** Entités HTML courantes du wire:snapshot (le JSON de Livewire échappe " et &). */
    private fun decoderEntites(s: String): String = s
        .replace("&quot;", "\"").replace("&amp;", "&")
        .replace("&#039;", "'").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    /** Clé API northlive : identique pour toutes les chaînes (mesuré), jamais en dur ;
     *  lue dans le player_url d'une page de chaîne, gardée ~1 h. */
    private suspend fun cleApi(slug: String): String {
        cleApiCache?.takeIf { System.currentTimeMillis() - it.first < TTL_CLE_MS }?.let { return it.second }
        return verrouCle.withLock {
            cleApiCache?.takeIf { System.currentTimeMillis() - it.first < TTL_CLE_MS }?.second ?: run {
                val player = playerUrlDans(get("$BASE/live-broadcast/$slug"))
                    ?: throw Exception("player_url introuvable ($slug)")
                val cle = RX_CLE_API.find(player)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                    ?: throw Exception("clé API introuvable ($slug)")
                cleApiCache = System.currentTimeMillis() to cle
                Log.i(TAG, "clé API northlive lue (${cle.length} caractères)")
                cle
            }
        }
    }

    /** En-têtes du flux : Referer = URL du lecteur northlive, EXIGÉ sur la playlist ET les
     *  segments (mesuré : 403 sans lui) ; cookies inutiles (credentials « omit » du lecteur). */
    private fun entetes(playerUrl: String): Map<String, String> = mapOf(
        "User-Agent" to NetworkClient.USER_AGENT,
        "Referer" to playerUrl,
        "Origin" to NORTH,
        "Accept" to "*/*",
    )

    private suspend fun lireFlux(slug: String): Video {
        val cle = cleApi(slug)
        val playerUrl = "$NORTH/api/v1/index.php?route=tv/$slug/player&api_key=$cle"
        val jeton = RX_PLAY_TOKEN.find(get(playerUrl, referer = "$BASE/live-broadcast/$slug"))
            ?.groupValues?.get(1) ?: throw Exception("PLAY_TOKEN introuvable ($slug)")
        val corps = JSONObject()
            .put("slug", slug).put("api_key", cle).put("play_token", jeton).toString()
        val reponse = client.newCall(
            Request.Builder()
                .url("$NORTH/api/v1/index.php?route=play_url&api_key=$cle")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $cle")
                .header("Origin", NORTH)
                .header("Referer", playerUrl)
                .post(corps.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw Exception("play_url HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val j = JSONObject(reponse)
        if (!j.optBoolean("success")) {
            throw Exception("play_url : " + j.optString("error").ifBlank { "success=false" })
        }
        val brute = j.optString("url").takeIf { it.isNotBlank() } ?: throw Exception("play_url sans url")
        var url = if (brute.startsWith("http")) brute else NORTH + brute
        if (!url.contains("pt=")) {
            // pt= n'accepte pas les « + » et « / » du jeton tels quels (le lecteur du site
            //   passe par URLSearchParams, qui les encode).
            url += (if (url.contains('?')) "&" else "?") + "pt=" + URLEncoder.encode(jeton, "UTF-8")
        }
        Log.i(TAG, "lecture $slug → flux northlive (${url.substringBefore('?').takeLast(40)})")
        return Video(
            source = url,
            headers = entetes(playerUrl),
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
        )
    }
}
