package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * ArchiveOrgProvider — backup NATIF par TITRE (archive.org, 2026-08-11).
 *
 * Origine : le user (« tu vas ajouter archivesorg à l'application comme tu as fait pour ok.ru…
 * on peut choper des très vieilles séries dedans ») avec, en exemple, La Quatrième Dimension
 * en VF. Internet Archive héberge des séries des années 50-70 doublées en français que plus
 * aucun site de streaming n'indexe.
 *
 * ── CE QUI CHANGE PAR RAPPORT À OK.RU ────────────────────────────────────────────────
 * Sur ok.ru, une page = une vidéo. Ici un « item » est un CONTENEUR : celui de La Quatrième
 * Dimension porte 789 fichiers dont 310 vidéos, soit la série entière. La recherche se fait
 * donc en DEUX ÉTAGES :
 *
 *   1. GET /advancedsearch.php?q=title:("…") AND mediatype:(movies)&output=json
 *      → response.docs[] = { identifier, title, year, language }
 *   2. GET /metadata/<identifier>
 *      → files[] = { name, format, source, original, length (s), width, height }
 *      C'est LÀ qu'on cherche l'épisode, par son nom de fichier.
 *
 * Conséquence heureuse : un seul item trouvé couvre toute une saison, parfois toute la série.
 *
 * ── LECTURE ──────────────────────────────────────────────────────────────────────────
 *   https://archive.org/serve/<identifier>/<nom de fichier encodé>
 * Redirige (302) vers un nœud `dnXXXXXX.us.archive.org`, répond 206 sur une requête Range,
 * `Content-Type: video/mp4`. Aucune signature, aucune expiration, aucun lien à l'IP —
 * contrairement à ok.ru, l'URL est stable et peut être servie telle quelle à ExoPlayer.
 *
 * ⚠ `/serve/` ET PAS `/download/` (2026-08-11, user : « pourquoi il met autant de temps à
 *   se lancer, alors que ça marche sur le site d'archive.org ? »). Les deux chemins mènent
 *   au même fichier, mais pas avec le même traitement : `/download/` est la porte du
 *   TÉLÉCHARGEMENT, bridée et instable ; `/serve/` est celle du STREAMING, et c'est elle
 *   que le lecteur du site utilise — relevée dans le shadow DOM de leur page :
 *     <video src="https://archive.org/serve/twilight-zone-…/Twilight Zone - … .mp4">
 *   Mesuré sur le même fichier, 3 essais chacun :
 *     /download/  →  2 échecs sur 3, HTTP 500 Internal Server Error
 *     /serve/     →  3 réussites sur 3, HTTP 206
 *   C'est ce qui expliquait les 79 s de démarrage : ExoPlayer se prenait des 500 et
 *   retentait jusqu'à tomber sur une réponse.
 *
 * ── TROIS PIÈGES MESURÉS (chacun avait cassé un cas de test) ──────────────────────────
 *   1. Zorro numérote ses fichiers `Zorro FR 01x01 - …`, PAS `S01E01`. Les deux formes sont
 *      obligatoires, sinon la série entière est invisible.
 *   2. « Chapeau Melon … S1-3 … VOstFR » est déclaré `language: eng` par l'uploader alors que
 *      tous ses fichiers sont sous-titrés français. La langue déclarée par l'item est donc le
 *      DERNIER recours : on lit d'abord le nom du fichier, puis le titre de l'item.
 *   3. Les séries sont découpées en items par plage de saisons (« S1-3 », « S4-5 », « S6 »).
 *      Sans lire cette plage dans le titre, on interrogeait 4 items au hasard : 24 s pour
 *      rendre 0 sur un épisode qui existait. Avec, l'item est le bon du premier coup (1,5 s).
 *
 * ── RÈGLE DE LANGUE (posée par le user, la même que pour ok.ru) ───────────────────────
 * STRICT FRANÇAIS. Pas de VO. Un fichier dont on ne peut pas prouver qu'il est VF ou VOSTFR
 * n'est pas proposé du tout — mieux vaut un serveur de moins qu'un serveur qui ment. Et les
 * sources qui n'ont que du VOSTFR sont étiquetées « VOSTFR » en clair sur le serveur.
 */
object ArchiveOrgProvider {
    private const val TAG = "ArchiveOrg"
    private const val BASE = "https://archive.org"
    const val SRC_PREFIX = "archiveorg::"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** Candidats demandés à la recherche. Au-delà, ce n'est plus que du bruit YouTube. */
    private const val NB_RESULTATS = 25

    /**
     * Plafond de fiches `/metadata` téléchargées, TOUS titres confondus.
     * Une fiche pèse jusqu'à 450 ko (789 fichiers pour La Quatrième Dimension) : sans plafond,
     * 3 titres × 4 items = 12 requêtes lourdes sur une box TV. Mesuré : le bon item est
     * quasiment toujours le premier une fois le score appliqué.
     */
    private const val MAX_FICHES = 5

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

    /** Marqueurs de sous-titrage français. Testés AVANT ceux de VF : « vostfr » contient « fr ». */
    private val RE_VOSTFR = Regex("\\b(vostfr|vostf|vost fr|vost|subfr|sous titres?)\\b")

    /** Marqueurs de doublage français. */
    private val RE_VF = Regex(
        "\\b(french|truefrench|vf|vff|vfq|vfi|francais|version francaise|fr)\\b"
    )

    /** Langues d'item qui valent « français » sans autre preuve. */
    private val LANGUES_FR = setOf("fre", "fra", "fr", "french", "francais", "français")

    /**
     * Formats de fichier qu'archive.org déclare pour de la vidéo. `h.264 IA` est le dérivé
     * que le site fabrique lui-même ; il sert de secours quand l'original est un .avi ou un
     * .ogv qu'ExoPlayer ne lit pas.
     */
    private val FORMATS_VIDEO = setOf(
        "MPEG4", "h.264", "h.264 IA", "512Kb MPEG4", "HiRes MPEG4", "Matroska", "WebM",
    )

    /** Extensions réellement lisibles par ExoPlayer sans transmuxage. */
    private val EXT_LISIBLES = listOf(".mp4", ".m4v", ".webm", ".mkv")

    private data class Item(
        val id: String,
        val titre: String,
        val langue: String,
        val annee: String,
    )

    private data class Fichier(
        val nom: String,
        val dureeS: Int,
        val hauteur: Int,
    )

    private fun http(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        client.newCall(req).execute().use { it.body?.string() }
    } catch (e: Exception) {
        Log.w(TAG, "GET ${url.take(90)} : ${e.message}"); null
    }

    /**
     * Recherche d'items. `mediatype:(movies)` couvre films ET séries chez Internet Archive :
     * il n'existe pas de type « série ». Le tri fin se fait ensuite dans [scorer].
     */
    private fun rechercher(titre: String): List<Item> {
        val q = "title:(\"${titre.replace("\"", " ")}\") AND mediatype:(movies)"
        val url = BASE + "/advancedsearch.php?q=" + enc(q) +
            "&rows=$NB_RESULTATS&page=1&output=json" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=language"

        val txt = http(url) ?: return emptyList()
        return try {
            val docs = JSONObject(txt).optJSONObject("response")?.optJSONArray("docs")
                ?: return emptyList()
            (0 until docs.length()).mapNotNull { i ->
                val d = docs.optJSONObject(i) ?: return@mapNotNull null
                val id = d.optString("identifier", "").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                // Les rips YouTube sont massivement présents et ne sont jamais l'œuvre :
                //   sur « Star Trek », 18 des 25 résultats bruts en étaient.
                if (id.startsWith("youtube-")) return@mapNotNull null
                Item(
                    id = id,
                    titre = d.optString("title", ""),
                    langue = d.optString("language", ""),
                    annee = d.optString("year", ""),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse recherche : ${e.message}"); emptyList()
        }
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val RE_PLAGE_SAISONS = Regex("\\bs(\\d{1,2})\\s*[-–a]\\s*s?(\\d{1,2})\\b")
    private val RE_SAISON = Regex("(?:\\bs|saison\\s*|season\\s*)(\\d{1,2})\\b")

    /**
     * Saisons annoncées par le titre d'un item (« … S1-3 … », « … Saison 2 … »), ou `null`
     * si le titre n'en annonce aucune — auquel cas l'item est présumé tout contenir, ce qui
     * est le cas de La Quatrième Dimension (« ☆ V.F. ☆ 1959-1964 », les 5 saisons dedans).
     *
     * ⚠ On travaille sur le titre BRUT (accents retirés, ponctuation conservée) : `norm`
     *   transformerait « S1-3 » en « s1 3 » et la plage serait perdue.
     */
    private fun couvertureSaisons(titreBrut: String): Set<Int>? {
        val t = sansAccents(titreBrut).lowercase().replace(Regex("[^a-z0-9\\s\\-]+"), " ")
        val couv = linkedSetOf<Int>()
        for (m in RE_PLAGE_SAISONS.findAll(t)) {
            val a = m.groupValues[1].toIntOrNull() ?: continue
            val b = m.groupValues[2].toIntOrNull() ?: continue
            if (a in 1..40 && b in a..40) couv.addAll(a..b)
        }
        if (couv.isEmpty()) {
            for (m in RE_SAISON.findAll(t)) {
                val n = m.groupValues[1].toIntOrNull() ?: continue
                if (n in 1..40) couv.add(n)
            }
        }
        return couv.ifEmpty { null }
    }

    /**
     * Score de pertinence d'un item, calculé UNIQUEMENT sur ce que la recherche a déjà rendu :
     * aucune requête supplémentaire. Un score ≤ 0 fait sauter l'item AVANT sa fiche `/metadata`,
     * et c'est tout l'intérêt — un item ni français ni marqué FR serait de toute façon rejeté à
     * l'étape langue, autant ne pas payer 450 ko pour l'apprendre.
     *
     * Mesuré sur « Zorro » : 23 items portent le mot dans leur titre, 2 survivent au score, et
     * le bon est le premier. Sur « Chapeau melon » : c'est le tri par plage de saisons qui met
     * l'item « S1-3 » devant « S4-5 », « S6 » et « S7-8 » — sans lui on rendait 0 en 24 s.
     */
    private fun scorer(item: Item, saison: Int, estEpisode: Boolean): Int {
        var s = 0
        if (item.langue.lowercase() in LANGUES_FR) s += 100
        val n = norm(item.titre)
        if (RE_VOSTFR.containsMatchIn(n) || RE_VF.containsMatchIn(n)) s += 80
        if (s > 0 && estEpisode) {
            val couv = couvertureSaisons(item.titre)
            if (couv != null) s += if (saison in couv) 60 else -150
        }
        return s
    }

    /**
     * Vidéos d'un item, une entrée par épisode réel.
     *
     * archive.org sert souvent DEUX fichiers pour le même contenu : l'original de l'uploader
     * et son dérivé `…​.ia.mp4`. On les regroupe par leur original commun et on n'en garde
     * qu'un — l'original s'il est déjà lisible, sinon le dérivé. Sans ce regroupement, chaque
     * épisode donnerait deux serveurs identiques dans la liste.
     */
    private fun videosDe(meta: JSONObject): List<Fichier> {
        val arr = meta.optJSONArray("files") ?: return emptyList()
        val groupes = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            if (f.optString("format") !in FORMATS_VIDEO) continue
            val nom = f.optString("name", "").takeIf { it.isNotBlank() } ?: continue
            val cle = if (f.optString("source") == "derivative")
                f.optString("original", nom) else nom
            groupes.getOrPut(cle) { mutableListOf() }.add(f)
        }
        return groupes.values.mapNotNull { variantes ->
            val lisible = { f: JSONObject ->
                EXT_LISIBLES.any { f.optString("name").endsWith(it, ignoreCase = true) }
            }
            val choisi = variantes.firstOrNull { it.optString("source") == "original" && lisible(it) }
                ?: variantes.firstOrNull(lisible)
                ?: return@mapNotNull null
            Fichier(
                nom = choisi.optString("name"),
                dureeS = choisi.optString("length").toDoubleOrNull()?.toInt() ?: 0,
                hauteur = choisi.optString("height").toIntOrNull() ?: 0,
            )
        }
    }

    /**
     * Position de l'épisode dans un nom de fichier.
     * DEUX écritures, obligatoirement les deux — mesuré : La Quatrième Dimension écrit
     * `S1E02`, Zorro écrit `01x01`. Les zéros de tête sont facultatifs des deux côtés.
     */
    private fun regexEpisode(saison: Int, episode: Int) = Regex(
        "(s0*${saison}\\s*[e\\-. ]\\s*0*${episode}|0*${saison}x0*${episode})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * VF, VOSTFR… ou rien du tout ?
     *
     * Trois sources de preuve, dans cet ORDRE — l'ordre est le fond du sujet :
     *   1. le NOM DU FICHIER (« … - VOstFR.mp4 », « … FRENCH … ») ;
     *   2. le TITRE DE L'ITEM (« ☆ V.F. ☆ », « ☆ VOstFR ☆ ») ;
     *   3. la LANGUE DÉCLARÉE de l'item, et seulement en dernier.
     *
     * ⚠ Pourquoi la langue déclarée passe en dernier : l'item « Chapeau Melon … S1-3 … VOstFR »
     *   est enregistré `language: eng` alors que 100 % de ses fichiers sont sous-titrés
     *   français. S'y fier d'abord, c'était perdre la série entière. À l'inverse, l'item de
     *   La Quatrième Dimension est déclaré `fre` mais contient un fichier « … VOstFR 1958 »
     *   (l'épisode pilote) : là c'est le nom du fichier qui rectifie, et il gagne.
     *
     * @return « VF », « VOSTFR », ou `null` — et `null` veut dire QUE LA SOURCE EST ÉCARTÉE.
     *   C'est la règle posée par le user pour ok.ru et reprise ici : pas de VO, et surtout
     *   pas de VO étiquetée VF.
     */
    private fun etiquetteLangue(nomFichier: String, titreItem: String, langueItem: String): String? {
        for (source in listOf(norm(nomFichier), norm(titreItem))) {
            if (RE_VOSTFR.containsMatchIn(source)) return "VOSTFR"
            if (RE_VF.containsMatchIn(source)) return "VF"
        }
        if (langueItem.lowercase() in LANGUES_FR) return "VF"
        return null
    }

    /**
     * Point d'entrée backup.
     *
     * @param titres      titres connus de l'œuvre (TMDB + alternatifs), essayés dans l'ordre.
     * @param runtimeMinS durée attendue la plus COURTE, en secondes (TMDB).
     * @param runtimeMaxS durée attendue la plus LONGUE, en secondes (TMDB).
     *
     * ⚠ Deux bornes, et pas une seule : TMDB déclare une LISTE de durées pour une série, et
     *   beaucoup mélangent les formats. La Quatrième Dimension annonce 25 min ET 51 min. La
     *   1ʳᵉ version ne prenait que la plus longue : l'épisode S1E03, 1471 s, se faisait
     *   écarter par un plancher à 2448 s alors qu'il était trouvé, identifié et correct.
     */
    suspend fun fetchArchiveOrgBackupServers(
        titres: List<String>,
        saison: Int,
        episode: Int,
        annee: Int?,
        runtimeMinS: Int? = null,
        runtimeMaxS: Int? = null,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val propres = titres
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .filter { norm(it).length >= 3 }
            .distinct().take(3)
        if (propres.isEmpty()) return@withContext emptyList()

        val estEpisode = saison > 0 && episode > 0
        val out = LinkedHashMap<String, Video.Server>()
        var fiches = 0

        for (titre in propres) {
            val nTitre = norm(titre)
            val candidats = rechercher(titre)
                .filter { nTitre.isNotBlank() && norm(it.titre).contains(nTitre) }
                .map { it to scorer(it, saison, estEpisode) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .map { it.first }

            Log.i(TAG, "'$titre' → ${candidats.size} item(s) français : " +
                candidats.take(4).joinToString { it.id.take(38) })

            for (item in candidats) {
                if (fiches >= MAX_FICHES) break
                fiches++
                val txt = http("$BASE/metadata/${item.id}") ?: continue
                val meta = try { JSONObject(txt) } catch (e: Exception) {
                    Log.w(TAG, "metadata ${item.id} illisible : ${e.message}"); continue
                }
                // Un item « dark » (retiré, ou métadonnées invalides) rend `{}` ou une erreur :
                //   pas de clé `files`. Fréquent sur les vieux uploads — on passe au suivant.
                if (!meta.has("files")) {
                    Log.i(TAG, "${item.id.take(38)} : item sans fichiers"); continue
                }
                val md = meta.optJSONObject("metadata") ?: JSONObject()
                val titreItem = md.optString("title", item.titre)
                val langueItem = md.optString("language", item.langue)
                val anneeItem = md.optString("year", item.annee) + " " + md.optString("date", "")

                val videos = videosDe(meta)
                val retenus = if (estEpisode) {
                    val re = regexEpisode(saison, episode)
                    videos.filter { re.containsMatchIn(sansAccents(it.nom)) }
                } else {
                    // Film : l'item EST le film. On prend le plus long fichier, et l'année doit
                    //   se retrouver quelque part dans l'item — même gate que les autres backups.
                    val an = annee?.takeIf { it > 1900 }?.toString()
                    if (an != null && !("$anneeItem $titreItem").contains(an)) emptyList()
                    else videos.maxByOrNull { it.dureeS }?.let { listOf(it) } ?: emptyList()
                }

                for (f in retenus) {
                    // ── GARDE-FOU DE DURÉE ────────────────────────────────────────────
                    // (user, 2026-08-11 : « les pièges tu peux les détecter par rapport à la
                    //   longueur du film ou à la longueur de la série, et tu les refuses »)
                    //
                    // C'est le filtre le plus rentable de tout le provider. Mesuré sur
                    //   « La Nuit des morts-vivants » : archive.org rend TROIS items marqués
                    //   « (1968) FRENCH », titre exact, année exacte, langue exacte — et leurs
                    //   fichiers font 200 s et 68 s. Ce sont des bandes-annonces. Tous les
                    //   autres tests les auraient laissées passer ; seule la durée les tue.
                    if (!dureeCredible(f.dureeS, runtimeMinS, runtimeMaxS, estEpisode)) {
                        Log.i(TAG, "ÉCARTÉ (durée ${f.dureeS}s, attendu " +
                            "${runtimeMinS ?: "?"}-${runtimeMaxS ?: "?"}s) « ${f.nom.take(60)} »")
                        continue
                    }
                    val langue = etiquetteLangue(f.nom, titreItem, langueItem)
                    if (langue == null) {
                        Log.i(TAG, "ÉCARTÉ (VO) « ${f.nom.take(60)} »"); continue
                    }
                    val cle = item.id + "/" + f.nom
                    if (out.containsKey(cle)) continue
                    val def = if (f.hauteur > 0) " · ${f.hauteur}p" else ""
                    out[cle] = Video.Server(
                        id = SRC_PREFIX + cle,
                        name = "archive.org$def · $langue",
                        src = "$BASE/serve/${item.id}/${enc(f.nom)}",
                    )
                    Log.i(TAG, "retenu [$langue] ${f.dureeS / 60} min — ${f.nom.take(70)}")
                }
                if (out.isNotEmpty()) break
            }
            if (out.isNotEmpty() || fiches >= MAX_FICHES) break
        }
        Log.i(TAG, "→ ${out.size} serveur(s) ($fiches fiche(s) lue(s))")
        out.values.toList()
    }

    /**
     * La durée du fichier est-elle compatible avec l'œuvre demandée ?
     *
     * Avec les durées TMDB : plancher à −20 % de la plus COURTE, plafond à +25 % de la plus
     * LONGUE. Assez large pour absorber génériques, coupures publicitaires d'une diffusion
     * d'époque, arrondis d'encodeur — et pour une série qui mélange les formats.
     * Sans elles : un simple plancher, qui suffit déjà à écarter bandes-annonces et extraits.
     * `0` = durée non déclarée par archive.org → on laisse passer, les autres filtres restent.
     */
    private fun dureeCredible(
        dureeS: Int,
        runtimeMinS: Int?,
        runtimeMaxS: Int?,
        estEpisode: Boolean,
    ): Boolean {
        if (dureeS <= 0) return true
        val bas = runtimeMinS?.takeIf { it > 0 } ?: runtimeMaxS?.takeIf { it > 0 }
        val haut = runtimeMaxS?.takeIf { it > 0 } ?: runtimeMinS?.takeIf { it > 0 }
        if (bas != null && haut != null)
            return dureeS >= bas * 0.80 && dureeS <= haut * 1.25
        return dureeS >= if (estEpisode) 15 * 60 else 55 * 60
    }

    /**
     * Lecture. Rien à résoudre : `/download/<item>/<fichier>` est une URL stable qui redirige
     * vers un nœud de stockage et répond aux requêtes Range. On la sert telle quelle.
     */
    fun getVideo(server: Video.Server): Video {
        val url = server.src.takeIf { it.startsWith("http") }
            ?: throw Exception("archive.org : URL absente")
        val bas = url.substringAfterLast('.').lowercase()
        val type = when {
            bas.startsWith("webm") -> androidx.media3.common.MimeTypes.VIDEO_WEBM
            bas.startsWith("mkv") -> androidx.media3.common.MimeTypes.VIDEO_MATROSKA
            else -> androidx.media3.common.MimeTypes.VIDEO_MP4
        }
        Log.d(TAG, "getVideo $type ← ${url.take(110)}")
        return Video(
            source = url,
            type = type,
            headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "$BASE/",
            ),
        )
    }
}
