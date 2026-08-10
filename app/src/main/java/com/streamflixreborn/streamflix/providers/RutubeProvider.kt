package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * RutubeProvider — backup NATIF par TITRE (rutube.ru, 2026-08-13).
 *
 * Origine : le user a trouvé Rutube (« ça va faire comme ok.ru / archive.org, on aura du
 * contenu supplémentaire »). Plateforme vidéo russe pleine de films et de vieilles séries
 * doublés/sous-titrés en français que les sites FR de l'app n'indexent pas. API PROPRE,
 * anonyme, sans Cloudflare (rétro-conçue en direct dans le Chrome du user) :
 *
 *   Recherche : GET /api/search/video/?query=<titre>&page=1
 *               → results[] = { id (32 hex), title, duration (SECONDES), is_adult, is_paid, … }
 *   Lecture   : GET /api/play/options/<id>/?no_404=true&pver=v2
 *               → video_balancer.m3u8 = master HLS (token CDN, lié à la requête) + captions[]
 *
 * ⚠ NOISE ÉNORME (comme ok.ru) : « Astérix » rend 97 résultats — bandes-annonces (112 s),
 *   critiques, autres films de la franchise, sets du DJ « Astrix ». Le titre COMPLET + la
 *   DURÉE TMDB sont décisifs. Principe user : « pas de serveur plutôt que le mauvais film ».
 *
 * ⚠ LANGUE : Rutube n'expose PAS la langue de l'audio (audiotracks vide, aucun manifeste DASH
 *   sondable comme chez ok.ru). On étiquette donc par le TITRE : marqueur VF/VOSTFR explicite,
 *   ou — à défaut — titre qui contient le titre FRANÇAIS demandé (ex. « Inspecteur Derrick »
 *   EST le titre français, sans tag). Sans aucun de ces signaux → on ÉCARTE (anti-VO).
 *
 * ⚠ is_paid = true → contenu premium/licencié (potentiellement DRM Widevine) → écarté à la
 *   recherche. Le flux gratuit est résolu À LA LECTURE (getVideo) car le m3u8 porte un token.
 */
object RutubeProvider {
    private const val TAG = "RutubeProvider"
    private const val BASE = "https://rutube.ru"
    const val SRC_PREFIX = "rutube::"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .dns(DnsResolver.doh)
            .build()
    }

    // ── normalisation ────────────────────────────────────────────────────────────────
    private fun sansAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun norm(s: String): String =
        sansAccents(s).lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Marqueurs de version française — audio VF ou sous-titres FR. */
    private val MARQUEUR_VF = Regex(
        "\\b(french|truefrench|vff?|vfq|vfi|vof|multi|francais|version francaise|en francais)\\b"
    )
    private val MARQUEUR_VOSTFR = Regex("\\b(vostfr|vost|subfr)\\b")

    /** Marqueurs de langue étrangère : rejet immédiat (Rutube est très international). */
    private val MARQUEUR_ETRANGER = Regex(
        "\\b(hun|ita|ger|deu|esp|spa|pol|cze|tur|ned|dut|swe|nor|fin|dan|por|jap|jpn|kor|hindi)\\b"
    )

    /** Mots NEUTRES d'un titre : n'identifient aucune œuvre (bruit de release / descriptif). */
    private val MOTS_NEUTRES = (
        "the a an of and or les la le de du des un une et en au aux dans sur " +
        "film complet gratuit online streaming vf vff vfq vfi vof vostfr multi french truefrench " +
        "francais version francaise hd sd full 480p 720p 1080p 2160p 4k webrip webdl bluray " +
        "saison season episode ep partie part serie integrale bande annonce trailer"
        ).split(" ").filter { it.isNotBlank() }.toSet()

    private data class Candidat(
        val id: String,
        val titre: String,
        val dureeS: Int,
    )

    // ── recherche ────────────────────────────────────────────────────────────────────
    private fun rechercher(requete: String): List<Candidat> {
        val url = "$BASE/api/search/video/?query=${enc(requete)}&page=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Referer", "$BASE/")
            .build()

        val txt = try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "recherche '$requete' HTTP ${r.code}"); null }
                else r.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "recherche '$requete' : ${e.message}"); null
        } ?: return emptyList()

        return try {
            val arr = JSONObject(txt).optJSONArray("results") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                // Écarter tout ce qui n'est pas une vidéo VOD lisible en clair.
                if (o.optBoolean("is_adult", false)) return@mapNotNull null
                if (o.optBoolean("is_paid", false)) return@mapNotNull null       // premium/DRM
                if (o.optBoolean("is_livestream", false)) return@mapNotNull null
                if (o.optBoolean("is_on_air", false)) return@mapNotNull null
                if (o.optBoolean("is_audio", false)) return@mapNotNull null
                if (o.optBoolean("is_deleted", false) || o.optBoolean("is_hidden", false)) return@mapNotNull null
                val id = o.optString("id", "").takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                    ?: return@mapNotNull null
                Candidat(id = id, titre = o.optString("title", ""), dureeS = o.optInt("duration", 0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse recherche : ${e.message}"); emptyList()
        }
    }

    /** Tous les mots SIGNIFICATIFS du titre de l'œuvre présents dans le candidat ? */
    private fun tousMotsPresents(nOeuvre: String, nCand: String): Boolean {
        val motsCand = nCand.split(" ").filter { it.isNotBlank() }.toSet()
        val sig = nOeuvre.split(" ").filter { it.length >= 2 && it !in MOTS_NEUTRES }
        if (sig.isEmpty()) return nCand.contains(nOeuvre)
        return sig.all { it in motsCand }
    }

    /**
     * MATCHING STRICT (le user : « recherche stricte car il y a pas mal de mauvaises choses »).
     * Cinq garde-fous cumulés :
     *   1. tous les mots du titre de l'œuvre présents dans le candidat ;
     *   2. aucun mot INTERDIT (autres saisons/films de la franchise, fourni par le registre) ;
     *   3. aucun marqueur de langue étrangère (hongrois, italien, allemand…) ;
     *   4. SxxExx (ou titre FR d'épisode) pour un épisode ; année pour un film ;
     *   5. durée dans la fenêtre TMDB (−20 % / +25 %) — élimine bandes-annonces, extraits,
     *      critiques, compilations, qui forment l'essentiel du bruit.
     * On ne fait PAS le « contrôle de franchise mot-à-mot » d'ok.ru : les titres Rutube sont
     * DESCRIPTIFS (acteurs, « film complet en français ») et non des noms de release ; ce
     * contrôle y recalerait des films légitimes. Le point 1 (tous les mots du titre présents)
     * + l'année + la durée suffisent à écarter les autres œuvres.
     */
    private fun retenir(
        candidats: List<Candidat>,
        titreOeuvre: String,
        saison: Int,
        episode: Int,
        annee: Int?,
        runtimeMinS: Int?,
        runtimeMaxS: Int?,
        titreEpisodeFr: String?,
        motsInterdits: Set<String>,
    ): List<Candidat> {
        val nOeuvre = norm(titreOeuvre)
        if (nOeuvre.isBlank()) return emptyList()
        val estEpisode = saison > 0 && episode > 0

        return candidats.filter { c ->
            val n = norm(c.titre)

            if (!tousMotsPresents(nOeuvre, n)) return@filter false
            if (motsInterdits.any { n.contains(it) }) return@filter false
            if (MARQUEUR_ETRANGER.containsMatchIn(n)) return@filter false

            if (estEpisode) {
                // Rutube nomme un épisode soit par son NOM (« Appel De Nuit »), soit par un code
                //   SSxEE / SxxExx (« 22x04 », « 08x10 », « s03e03 »). Lookbehind + lookahead
                //   anti-chiffre : « 23x03 » ne doit PAS matcher du S3E3, et le numéro absolu qui
                //   précède souvent (« Derrick 246 22x04 ») ne doit pas parasiter. On matche sur
                //   la chaîne ESPACÉE (les tokens sont séparés).
                val re = Regex("(?<![0-9])(s0*${saison}e0*${episode}|0*${saison}x0*${episode})(?![0-9])")
                val parCode = re.containsMatchIn(n)
                val parTitreEp = !titreEpisodeFr.isNullOrBlank() && n.contains(norm(titreEpisodeFr))
                if (!parCode && !parTitreEp) return@filter false
            } else if (annee != null && annee > 1900) {
                // On N'EXIGE PAS l'année (les uploads Rutube « Parker Film Complet En Français »
                //   ne la mettent souvent pas) : mais SI le titre porte une année, elle doit
                //   coller (±1) — écarte les remakes. Sans année dans le titre → titre + durée.
                val anneeTitre = Regex("\\b(19|20)\\d{2}\\b").find(n)?.value?.toIntOrNull()
                if (anneeTitre != null && kotlin.math.abs(anneeTitre - annee) > 1) return@filter false
            }

            // ── fenêtre de durée (le discriminant décisif) ────────────────────────────
            val bas = runtimeMinS?.takeIf { it > 0 }
            val haut = runtimeMaxS?.takeIf { it > 0 }
            if (bas != null && haut != null) {
                if (c.dureeS < bas * 0.80 || c.dureeS > haut * 1.25) return@filter false
            } else {
                // Sans runtime TMDB : un plancher suffit à écarter bandes-annonces et extraits.
                val plancher = if (estEpisode) 12 * 60 else 55 * 60
                if (c.dureeS in 1 until plancher) return@filter false
            }
            true
        }
    }

    /**
     * VF, VOSTFR… ou rien ? Rutube ne déclare pas la langue → on lit le TITRE.
     *   • tag VOSTFR                          → « VOSTFR »
     *   • tag FRENCH/VF/TRUEFRENCH/MULTI/…    → « VF »
     *   • pas de tag mais titre qui contient le TITRE FRANÇAIS demandé → « VF »
     *     (ex. « Inspecteur Derrick- Appel De Nuit » : pas de tag, mais c'est le titre FR)
     *   • sinon → null : LA SOURCE EST ÉCARTÉE (mieux vaut un serveur de moins qu'un serveur VO).
     */
    private fun etiquetteLangue(c: Candidat, nTitreFr: String): String? {
        val n = norm(c.titre)
        if (MARQUEUR_VOSTFR.containsMatchIn(n)) return "VOSTFR"
        if (MARQUEUR_VF.containsMatchIn(n)) return "VF"
        if (nTitreFr.isNotBlank() && tousMotsPresents(nTitreFr, n)) return "VF"
        Log.d(TAG, "écarté « ${c.titre} » — aucun signal FR (VO probable)")
        return null
    }

    /**
     * Point d'entrée backup.
     *
     * @param titres       titres connus de l'œuvre (TMDB + alternatifs), essayés dans l'ordre.
     * @param titreFr      titre FRANÇAIS demandé (= key.title côté registre) — preuve de version
     *                     française quand le nom de la vidéo ne porte aucun tag.
     * @param runtimeMinS  durée mini attendue (secondes) ; @param runtimeMaxS durée maxi.
     */
    suspend fun fetchRutubeBackupServers(
        titres: List<String>,
        titreFr: String,
        saison: Int,
        episode: Int,
        annee: Int?,
        runtimeMinS: Int?,
        runtimeMaxS: Int?,
        titreEpisodeFr: String? = null,
        motsInterdits: Set<String> = emptySet(),
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val propres = titres
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .filter { norm(it).isNotBlank() }      // titres cyrilliques/CJK → norm vide → écartés
            .distinct().take(3)
        if (propres.isEmpty()) return@withContext emptyList()
        val estEpisode = saison > 0 && episode > 0
        val nTitreFr = norm(titreFr)
        val out = LinkedHashMap<String, Video.Server>()

        for (titre in propres) {
            // Requêtes en langage naturel (Rutube titre en clair, pas en nom de release).
            val requetes = if (estEpisode) listOf(
                "$titre S%02dE%02d".format(saison, episode),
                titre,
            ) else if (annee != null && annee > 1900) listOf(
                "$titre $annee",
                titre,
            ) else listOf(titre)

            val bruts = LinkedHashMap<String, Candidat>()
            for (r in requetes) for (c in rechercher(r)) bruts.putIfAbsent(c.id, c)
            val gardes = retenir(
                bruts.values.toList(), titre, saison, episode, annee,
                runtimeMinS, runtimeMaxS, titreEpisodeFr, motsInterdits,
            )
            Log.i(TAG, "rutube '${requetes.joinToString(" + ")}' → ${bruts.size} bruts, ${gardes.size} retenus")
            for (c in gardes) {
                if (out.containsKey(c.id)) continue
                val langue = etiquetteLangue(c, nTitreFr) ?: continue   // null = VO → on n'en veut pas
                out[c.id] = Video.Server(
                    id = SRC_PREFIX + c.id,
                    name = "Rutube · $langue",
                    src = "$BASE/video/${c.id}/",
                )
            }
            if (out.isNotEmpty()) break
        }
        out.values.toList()
    }

    // ── lecture ──────────────────────────────────────────────────────────────────────
    /** Résout un id Rutube en flux HLS. À LA LECTURE : l'URL m3u8 porte un token éphémère. */
    fun resolveById(id: String): Video {
        val url = "$BASE/api/play/options/$id/?no_404=true&referer=${enc("$BASE/")}&pver=v2"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Referer", "$BASE/")
            .build()

        val txt = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Rutube play/options HTTP ${r.code}")
            r.body?.string()
        } ?: throw Exception("Rutube : play/options vide")

        val j = JSONObject(txt)
        val m3u8 = j.optJSONObject("video_balancer")?.optString("m3u8", "")
            ?.takeIf { it.startsWith("http") }
            ?: throw Exception("Rutube : aucun flux (contenu DRM/premium ?)")

        // Sous-titres éventuels (rares) — on prend en charge sans en dépendre.
        val subs = j.optJSONArray("captions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val cc = arr.optJSONObject(i) ?: return@mapNotNull null
                val file = (cc.optString("file", "").ifBlank { cc.optString("url", "") })
                    .takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val code = cc.optString("code", cc.optString("lang", "")).lowercase()
                val fr = code.startsWith("fr")
                Video.Subtitle(
                    label = if (fr) "Français" else cc.optString("langTitle", code).ifBlank { code.uppercase() },
                    file = file,
                    default = fr,
                )
            }
        }.orEmpty()

        Log.d(TAG, "getVideo $id → HLS, sous-titres: ${subs.joinToString { it.label }.ifBlank { "aucun" }}")
        return Video(
            source = m3u8,
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            subtitles = subs,
            headers = mapOf("User-Agent" to UA, "Referer" to "$BASE/"),
        )
    }

    fun getVideo(server: Video.Server): Video {
        val id = server.id.removePrefix(SRC_PREFIX).takeIf { it.matches(Regex("[a-f0-9]{32}")) }
            ?: Regex("[a-f0-9]{32}").find(server.src)?.value
            ?: throw Exception("Rutube : id absent de ${server.id}")
        return resolveById(id)
    }

    // ── NAVIGATION (dossier Rutube du TV Hub, 2026-08-13) ─────────────────────────────
    //   Ici on EXPLORE (comme sur le site) : pas de matching strict, on renvoie les clips
    //   tels quels pour la recherche navigable + les playlists de favoris. La lecture réutilise
    //   resolveById/getVideo (m3u8 à la lecture).

    /** Un clip Rutube pour l'affichage navigable (recherche, favoris, playlist). */
    data class RutubeClip(
        val id: String,
        val title: String,
        val author: String,
        val durationS: Int,
        val thumbnail: String,
    )

    /**
     * Recherche navigable MULTI-PAGES (user 2026-08-13 : « tu aurais pas bloqué la recherche
     * à 97 ? » — oui : une page Rutube ≈ 100 résultats, on s'arrêtait là).
     * On enchaîne les pages tant que le serveur annonce `has_next`, jusqu'à [maxPages].
     * user : « faut pas brider, au moins on accède à tout le contenu » → plafond volontairement
     * HAUT (≈1500 clips). Ce n'est PAS une limite de confort : la boucle s'arrête d'elle-même dès
     * que `has_next` retombe, donc on ne paie que ce que la requête donne vraiment. Le garde-fou
     * n'existe que pour ne pas boucler à l'infini sur un terme ultra-générique.
     */
    suspend fun searchClipsPaged(query: String, maxPages: Int = 15): List<RutubeClip> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, RutubeClip>()
            for (p in 1..maxPages) {
                val (clips, hasNext) = searchPage(query, p)
                clips.forEach { out.putIfAbsent(it.id, it) }
                if (!hasNext) break
            }
            Log.i(TAG, "searchClipsPaged '$query' → ${out.size} clips (≤$maxPages pages)")
            out.values.toList()
        }

    /** Une page de résultats + le drapeau « il en reste ». */
    private fun searchPage(query: String, page: Int): Pair<List<RutubeClip>, Boolean> {
        val url = "$BASE/api/search/video/?query=${enc(query)}&page=$page"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Referer", "$BASE/")
            .build()
        val txt = try {
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (e: Exception) { Log.w(TAG, "searchPage '$query' p$page: ${e.message}"); null }
            ?: return emptyList<RutubeClip>() to false
        return try {
            val root = JSONObject(txt)
            val arr = root.optJSONArray("results") ?: return emptyList<RutubeClip>() to false
            val clips = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optBoolean("is_adult", false) || o.optBoolean("is_deleted", false)) return@mapNotNull null
                if (o.optBoolean("is_hidden", false)) return@mapNotNull null
                val id = o.optString("id", "").takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                    ?: return@mapNotNull null
                RutubeClip(
                    id = id,
                    title = o.optString("title", ""),
                    author = o.optJSONObject("author")?.optString("name", "").orEmpty(),
                    durationS = o.optInt("duration", 0),
                    thumbnail = o.optString("thumbnail_url", ""),
                )
            }
            clips to root.optBoolean("has_next", false)
        } catch (e: Exception) { Log.w(TAG, "parse searchPage: ${e.message}"); emptyList<RutubeClip>() to false }
    }

    /** Recherche navigable : renvoie les clips bruts (films/clips/artistes), page paginée. */
    suspend fun searchClips(query: String, page: Int = 1): List<RutubeClip> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val url = "$BASE/api/search/video/?query=${enc(q)}&page=$page"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Referer", "$BASE/")
            .build()
        val txt = try {
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (e: Exception) { Log.w(TAG, "searchClips '$q': ${e.message}"); null }
            ?: return@withContext emptyList()
        try {
            val arr = JSONObject(txt).optJSONArray("results") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optBoolean("is_adult", false) || o.optBoolean("is_deleted", false)) return@mapNotNull null
                if (o.optBoolean("is_hidden", false)) return@mapNotNull null
                val id = o.optString("id", "").takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                    ?: return@mapNotNull null
                RutubeClip(
                    id = id,
                    title = o.optString("title", ""),
                    author = o.optJSONObject("author")?.optString("name", "").orEmpty(),
                    durationS = o.optInt("duration", 0),
                    thumbnail = o.optString("thumbnail_url", ""),
                )
            }
        } catch (e: Exception) { Log.w(TAG, "parse searchClips: ${e.message}"); emptyList() }
    }

    /** Métadonnées d'un clip par id (titre + vignette + durée), pour l'affichage à froid
     *  (ex. ouverture directe d'un clip favori sans passer par la recherche). */
    fun fetchClipMeta(id: String): RutubeClip? {
        if (!id.matches(Regex("[a-f0-9]{32}"))) return null
        val req = Request.Builder().url("$BASE/api/video/$id/")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Referer", "$BASE/")
            .build()
        val txt = try {
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (e: Exception) { Log.w(TAG, "fetchClipMeta $id: ${e.message}"); null } ?: return null
        return try {
            val o = JSONObject(txt)
            RutubeClip(
                id = id,
                title = o.optString("title", ""),
                author = o.optJSONObject("author")?.optString("name", "").orEmpty(),
                durationS = o.optInt("duration", 0),
                thumbnail = o.optString("thumbnail_url", ""),
            )
        } catch (e: Exception) { Log.w(TAG, "parse fetchClipMeta: ${e.message}"); null }
    }
}
