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

    /**
     * 2026-08-16 (user : « Rutube match sur n'importe quoi », cas Game of Thrones).
     * Vidéos qui PARLENT de l'œuvre au lieu de LA CONTENIR : conférences, critiques, analyses,
     * résumés, réactions, interviews, making-of… Rutube en est plein, et elles échappaient à
     * tous les garde-fous parce qu'elles citent le titre ET durent longtemps.
     * Cas prouvé : « George R.R. Martin Le Trone de fer B. Metraux #8 » — 92 min, une conférence
     * sur l'auteur, qui passait `tousMotsPresents` (« trone » + « fer ») puis le plancher de
     * durée, et se faisait étiqueter « VF » puisqu'elle contient le titre français demandé.
     */
    private val MARQUEUR_HORS_SUJET = Regex(
        "\\b(conference|critique|analyse|analyses|resume|resumes|recap|reaction|reactions|" +
        "interview|itw|making of|makingof|coulisses|explication|explique|decryptage|" +
        "commentaire|commentee|theorie|theories|top \\d+|classement|documentaire|" +
        "podcast|debat|chronique|avis|review|spoilers?|" +
        // 2026-08-16 (user : « Rutube continue à matcher avec des musiques portant le même nom
        //   que la série ») — génériques, BO, reprises, mixes… Ils citent le titre de l'œuvre
        //   et échappaient donc au filtre. Le plafond de durée en attrape une partie, mais une
        //   compilation d'une heure y survivrait : ces marqueurs ferment l'angle restant.
        "musique|music|chanson|generique|soundtrack|ost|bande originale|theme song|" +
        "cover|remix|mix|mashup|instrumental|karaoke|lyrics|paroles|audio officiel|" +
        "clip officiel|official video|official audio|feat|album|playlist|nightcore|" +
        "piano|guitare|violon|orchestre|concert|live session)\\b"
    )

    /**
     * 2026-08-16 — Couverture INVERSE : quelle part des mots significatifs du CANDIDAT est
     * expliquée par le titre de l'œuvre ?
     *
     * `tousMotsPresents` ne vérifie que ce que le candidat CONTIENT, jamais ce qu'il EST : rien
     * n'interdisait qu'il parle d'autre chose. Sur l'exemple ci-dessus, « trone » et « fer » ne
     * représentent que 2 mots sur 6 (george, martin, trone, fer, metraux…) → la vidéo porte
     * majoritairement sur autre chose.
     * Seuil VOLONTAIREMENT BAS (35 %) : les titres Rutube sont descriptifs (« … Film Complet En
     * Français », noms d'acteurs) et un seuil élevé recalerait des films légitimes — c'est
     * exactement la raison pour laquelle le contrôle mot-à-mot d'ok.ru avait été écarté ici.
     */
    private const val COUVERTURE_INVERSE_MIN = 0.50

    /**
     * Jetons TECHNIQUES d'un nom de release : ils ne parlent pas du sujet de la vidéo, donc ils
     * ne doivent PAS peser dans la couverture inverse. Sans ça, « Le Trone de fer S08E01 FRENCH
     * HDTV » tombait à 50 % (2 mots sur 4) et un jeton de plus (x264…) l'aurait fait rejeter,
     * alors que c'est exactement la release qu'on veut.
     */
    private val JETON_TECHNIQUE = Regex(
        "^(s\\d{1,3}e\\d{1,3}|\\d{1,3}x\\d{1,3}|x26[45]|h26[45]|xvid|divx|aac\\d*|ac3|dts|" +
        "hdtv|hdrip|dvdrip|brrip|bdrip|web|dl|webrip|amzn|nf|ddp\\d*|mkv|mp4|avi|" +
        "lostfilm|qqss\\d*|vostfr|multi|truefrench|french|\\d{3,4}p)$"
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

            // 2026-08-16 : la vidéo PARLE de l'œuvre au lieu de LA CONTENIR.
            if (MARQUEUR_HORS_SUJET.containsMatchIn(n)) {
                Log.d(TAG, "écarté « ${c.titre} » — hors-sujet (parle de l'œuvre)")
                return@filter false
            }

            // 2026-08-16 : couverture INVERSE — le titre de l'œuvre doit expliquer une part
            //   suffisante du candidat, sinon la vidéo porte majoritairement sur autre chose.
            //   (« George R.R. Martin Le Trone de fer B. Metraux #8 » : 2 mots sur 6.)
            val motsCandSig = n.split(" ")
                .filter { it.length >= 2 && it !in MOTS_NEUTRES && !JETON_TECHNIQUE.matches(it) }
            if (motsCandSig.isNotEmpty()) {
                // Sont « expliqués » les mots du titre de l'œuvre ET ceux du titre d'épisode :
                //   « Inspecteur Derrick- Appel De Nuit » est légitime, le nom de l'épisode n'est
                //   pas du hors-sujet. Sans ce crédit, les séries nommées par épisode chutaient.
                val motsOeuvre = (nOeuvre + " " + (titreEpisodeFr?.let { norm(it) } ?: ""))
                    .split(" ").filter { it.length >= 2 && it !in MOTS_NEUTRES }.toSet()
                val expliques = motsCandSig.count { it in motsOeuvre }
                if (expliques.toDouble() / motsCandSig.size < COUVERTURE_INVERSE_MIN) {
                    Log.d(TAG, "écarté « ${c.titre} » — couverture $expliques/${motsCandSig.size} < ${(COUVERTURE_INVERSE_MIN * 100).toInt()}%")
                    return@filter false
                }
            }

            if (estEpisode) {
                // Rutube nomme un épisode soit par son NOM (« Appel De Nuit »), soit par un code
                //   SSxEE / SxxExx (« 22x04 », « 08x10 », « s03e03 »). Lookbehind + lookahead
                //   anti-chiffre : « 23x03 » ne doit PAS matcher du S3E3, et le numéro absolu qui
                //   précède souvent (« Derrick 246 22x04 ») ne doit pas parasiter. On matche sur
                //   la chaîne ESPACÉE (les tokens sont séparés).
                val re = Regex("(?<![0-9])(s0*${saison}e0*${episode}|0*${saison}x0*${episode})(?![0-9])")
                val parCode = re.containsMatchIn(n)
                // 2026-08-16 : le titre d'épisode ne vaut comme preuve QUE s'il est
                //   DISCRIMINANT. TMDB renvoie souvent un libellé générique (« Épisode 1 »,
                //   « Episode 12 ») dont tous les mots sont neutres → `contains` devenait
                //   trivialement vrai et laissait entrer n'importe quelle vidéo de la série.
                //   On exige donc au moins un mot significatif (hors MOTS_NEUTRES, ≥ 3 lettres).
                val nEp = titreEpisodeFr?.let { norm(it) }.orEmpty()
                val epDiscriminant = nEp.split(" ")
                    .any { it.length >= 3 && it !in MOTS_NEUTRES && !it.all(Char::isDigit) }
                val parTitreEp = epDiscriminant && n.contains(nEp)
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
                // Sans runtime TMDB : plancher ET PLAFOND.
                // 2026-08-16 (user : « l'épisode où il a matché dure 3 h, ça se peut pas ») —
                //   il n'y avait AUCUN plafond ici, d'où des compilations musicales et des
                //   rediffusions de plusieurs heures acceptées comme épisodes.
                //   Pourquoi la borne TMDB ne jouait pas : `episode_run_time` est une liste, et
                //   TMDB la rend VIDE sur beaucoup de séries récentes — vérifié en direct,
                //   Game of Thrones renvoie `[]` (Dexter, lui, renvoie `[60]`). Les deux bornes
                //   tombaient donc à null et seul le plancher subsistait.
                //   Plafonds volontairement LARGES (marge pour les épisodes doubles, les
                //   pilotes rallongés et les vidéos un peu tronquées) : ils ne visent que
                //   l'aberration manifeste, pas l'ajustement fin.
                // 2026-08-16 (user, après plusieurs faux positifs : « il a encore matché sur
                //   un épisode de 37 min qui n'a rien à voir avec la série ») —
                //   ÉPISODE SANS DURÉE DE RÉFÉRENCE = REJET SEC.
                //   Le titre seul ne suffit pas à identifier un épisode sur Rutube : le site
                //   est plein de clips, génériques et vidéos de fans qui citent le nom de la
                //   série. Sans fenêtre de durée on ne peut RIEN vérifier — et c'est
                //   précisément le cas où les faux positifs sont passés (TMDB renvoie
                //   `episode_run_time: []` sur beaucoup de séries, dont Game of Thrones).
                //   Application directe du principe « pas de serveur plutôt que le mauvais ».
                //   Les séries dont TMDB déclare la durée (Dexter → [60]) ne sont PAS
                //   touchées : elles passent par la branche `bas/haut` ci-dessus.
                if (estEpisode) {
                    Log.d(TAG, "écarté « ${c.titre} » — épisode sans durée TMDB de référence")
                    return@filter false
                }
                // FILM : la durée TMDB manque rarement, et le titre + l'année discriminent.
                val plancher = 55 * 60
                val plafond = 240 * 60
                if (c.dureeS in 1 until plancher) return@filter false
                if (c.dureeS > plafond) {
                    Log.d(TAG, "écarté « ${c.titre} » — ${c.dureeS / 60} min > plafond ${plafond / 60} min")
                    return@filter false
                }
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
